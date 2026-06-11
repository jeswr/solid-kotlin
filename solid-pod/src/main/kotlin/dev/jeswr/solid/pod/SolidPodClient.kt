package dev.jeswr.solid.pod

import dev.jeswr.solid.oidc.HttpClient
import dev.jeswr.solid.oidc.HttpRequest
import dev.jeswr.solid.rdf.Graph
import dev.jeswr.solid.rdf.Term
import dev.jeswr.solid.rdf.Turtle
import java.net.URI

/**
 * An RDF resource read from a pod, with the validator for the conditional write
 * that follows (read → mutate → write `ifMatch:`).
 */
public class PodResource(
    /** The parsed resource. Empty when [exists] is false. */
    public var graph: Graph,
    /** Strong validator for `If-Match` writes; null when absent. */
    public val etag: String?,
    /** False when the server returned 404 — a "read for create", not an error. */
    public val exists: Boolean,
    /** The final resource URL (after redirects). */
    public val url: URI,
)

/** The result of a successful write. */
public class WriteResult(
    /** The new ETag if the server returned one. */
    public val etag: String?,
    public val status: Int,
    public val url: URI,
)

/** A container listing. */
public class ContainerListing(
    public val url: URI,
    /** The `ldp:contains` members, sorted. */
    public val members: List<URI>,
    /** The full container graph (for metadata beyond membership). */
    public val graph: Graph,
    public val etag: String?,
)

/**
 * Resource IO against a Solid pod: reads and conditional writes of RDF
 * resources, container operations, storage discovery, and WebID profile access.
 *
 * ```kotlin
 * val pod = SolidPodClient(session.authenticatedClient)
 * val resource = pod.readResource(noteURL)
 * resource.graph = resource.graph.insert(...)
 * pod.write(resource.graph, noteURL, ifMatch = resource.etag)
 * ```
 */
public class SolidPodClient(public val httpClient: HttpClient) {

    // Reading

    /**
     * Dereference an RDF resource. 404 resolves (not throws) with
     * `exists == false`; 401/403 throw the typed errors.
     */
    public fun readResource(url: URI): PodResource {
        val response = httpClient.send(
            HttpRequest(url = url.toString(), headers = mapOf("Accept" to "text/turtle")),
        )
        if (response.statusCode == 404) {
            return PodResource(Graph(), null, exists = false, url = url)
        }
        if (!response.isSuccess) {
            throw PodException.forStatus(response.statusCode, url, response.bodyText)
        }
        val documentURL = withoutFragment(URI(response.url))
        return try {
            val graph = Turtle.parse(response.bodyText, base = documentURL.toString())
            PodResource(graph, response.header("ETag"), exists = true, url = documentURL)
        } catch (e: Exception) {
            throw PodException.UnparsableResource(url, e.toString())
        }
    }

    // Writing

    /** Serialise [graph] as Turtle and conditionally PUT it. */
    public fun write(
        graph: Graph,
        url: URI,
        ifMatch: String? = null,
        prefixes: Map<String, String> = emptyMap(),
    ): WriteResult =
        put(Turtle.serialize(graph, prefixes).toByteArray(), "text/turtle", url, ifMatch)

    /** Conditionally PUT a non-RDF (or byte-exact) document verbatim. */
    public fun writeData(
        data: ByteArray,
        contentType: String,
        url: URI,
        ifMatch: String? = null,
    ): WriteResult = put(data, contentType, url, ifMatch)

    private fun put(body: ByteArray, contentType: String, url: URI, ifMatch: String?): WriteResult {
        val headers = HashMap<String, String>()
        headers["Content-Type"] = contentType
        if (ifMatch != null) headers["If-Match"] = ifMatch else headers["If-None-Match"] = "*"
        val response = httpClient.send(HttpRequest("PUT", url.toString(), headers, body))
        if (!response.isSuccess) {
            throw PodException.forStatus(response.statusCode, url, response.bodyText)
        }
        return WriteResult(response.header("ETag"), response.statusCode, url)
    }

    /** Delete a resource. */
    public fun delete(url: URI) {
        val response = httpClient.send(HttpRequest("DELETE", url.toString()))
        if (!response.isSuccess) {
            throw PodException.forStatus(response.statusCode, url, response.bodyText)
        }
    }

    // Containers

    /** Read a container and its `ldp:contains` members. */
    public fun listContainer(url: URI): ContainerListing {
        if (!url.toString().endsWith("/")) throw PodException.NotAContainer(url)
        val resource = readResource(url)
        if (!resource.exists) throw PodException.NotFound(url)
        val members = resource.graph
            .iriObjects(Term.IRI(resource.url.toString()), Term.IRI(Vocab.LDP_CONTAINS))
            .mapNotNull { runCatching { URI(it) }.getOrNull() }
        return ContainerListing(resource.url, members, resource.graph, resource.etag)
    }

    /**
     * Make sure the container at [url] exists (HEAD, then conditional PUT when
     * absent). Safe under concurrency: a raced create (412) resolves as false.
     * Returns whether this call created it.
     */
    public fun ensureContainer(url: URI): Boolean {
        if (!url.toString().endsWith("/")) throw PodException.NotAContainer(url)
        val head = httpClient.send(HttpRequest("HEAD", url.toString()))
        if (head.isSuccess) return false
        if (head.statusCode != 404) {
            throw PodException.forStatus(head.statusCode, url, "container existence check")
        }
        val response = httpClient.send(
            HttpRequest(
                "PUT",
                url.toString(),
                mapOf(
                    "Content-Type" to "text/turtle",
                    "Link" to "<${Vocab.LDP_BASIC_CONTAINER}>; rel=\"type\"",
                    "If-None-Match" to "*",
                ),
            ),
        )
        if (response.isSuccess) return true
        if (response.statusCode == 412) return false
        throw PodException.forStatus(response.statusCode, url, response.bodyText)
    }

    internal fun withoutFragment(url: URI): URI =
        runCatching { URI(url.scheme, url.authority, url.path, url.query, null) }.getOrDefault(url)

    /** The document URL and every ancestor container up to the origin root. */
    internal fun ancestors(resource: URI): List<URI> {
        val clean = runCatching { URI(resource.scheme, resource.authority, resource.path, null, null) }
            .getOrDefault(resource)
        val result = ArrayList<URI>()
        result.add(clean)
        var path = clean.path ?: ""
        while (path != "/" && path.isNotEmpty()) {
            if (path.endsWith("/")) path = path.dropLast(1)
            val lastSlash = path.lastIndexOf('/')
            if (lastSlash < 0) break
            path = path.substring(0, lastSlash + 1)
            result.add(URI(clean.scheme, clean.authority, path, null, null))
        }
        return result
    }
}
