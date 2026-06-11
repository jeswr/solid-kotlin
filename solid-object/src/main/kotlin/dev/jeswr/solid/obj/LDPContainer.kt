package dev.jeswr.solid.obj

import dev.jeswr.solid.rdf.RDF
import dev.jeswr.solid.rdf.Term
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * A typed view over an LDP container listing (the RDF a Solid server returns
 * when you `GET` a `…/` URL) — the Kotlin counterpart to `@solid/object`'s
 * `Container`.
 *
 * ```kotlin
 * val container = LDPContainer("https://alice.example/notes/", box)
 * for (child in container.contains) {
 *     println("${child.iri ?: "?"} ${if (child.isContainer) "[dir]" else "[file]"}")
 * }
 * ```
 */
public class LDPContainer(term: Term, graph: GraphBox) : TermWrapper(term, graph) {
    public constructor(iri: String, graph: GraphBox) : this(Term.IRI(iri), graph)

    /** The container's members (`ldp:contains`), each as an [LDPResource]. */
    public val contains: WrappedSet<LDPResource> by set(
        LDP.CONTAINS,
        TermAs.instance(::LDPResource),
        TermFrom.instance(),
    )

    /** Whether the listing types this resource as an LDP container. */
    public val isContainer: Boolean
        get() {
            val types = graph.graph.objects(term, Term.IRI(RDF.TYPE)).mapNotNull { it.iriValue }
            return LDP.CONTAINER in types || LDP.BASIC_CONTAINER in types
        }
}

/** A single member of an LDP container. */
public class LDPResource(term: Term, graph: GraphBox) : TermWrapper(term, graph) {
    public constructor(iri: String, graph: GraphBox) : this(Term.IRI(iri), graph)

    /**
     * Whether this member is itself a container (its IRI ends in `/`, or it is
     * typed `ldp:Container`).
     */
    public val isContainer: Boolean
        get() {
            iri?.let { if (it.endsWith("/")) return true }
            val types = graph.graph.objects(term, Term.IRI(RDF.TYPE)).mapNotNull { it.iriValue }
            return LDP.CONTAINER in types || LDP.BASIC_CONTAINER in types
        }

    /** The member's last path segment (its file/folder name), URL-decoded. */
    public val name: String?
        get() {
            val iri = iri ?: return null
            val trimmed = if (iri.endsWith("/")) iri.dropLast(1) else iri
            // URI.path already percent-decodes; fall back to the raw last
            // segment, then percent-decode it, if the IRI is not URI-parseable.
            val decodedPath = runCatching { URI(trimmed).path }.getOrNull()
            if (decodedPath != null) return decodedPath.substringAfterLast('/')
            val raw = trimmed.substringAfterLast('/')
            return runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8) }.getOrDefault(raw)
        }
}
