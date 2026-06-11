package dev.jeswr.solid.oidc

import dev.jeswr.solid.rdf.Term
import dev.jeswr.solid.rdf.Turtle
import java.net.URI

/**
 * Resolution of a login input (WebID or issuer URL) to an OIDC issuer, per the
 * Solid-OIDC primer: dereference the WebID profile and read its
 * `solid:oidcIssuer` triples.
 */
public object IssuerDiscovery {
    internal const val OIDC_ISSUER_PREDICATE = "http://www.w3.org/ns/solid/terms#oidcIssuer"

    /** The result of resolving a login input. */
    public data class Resolution(val issuer: URI, val webID: URI?)

    /**
     * The OIDC issuers a WebID profile advertises (may be empty). Throws only on
     * network failure or unparsable RDF.
     */
    public fun issuers(webID: URI, httpClient: HttpClient): List<URI> {
        val response = httpClient.send(
            HttpRequest(url = webID.toString(), headers = mapOf("Accept" to "text/turtle")),
        )
        if (!response.isSuccess) {
            throw SolidOidcException.IssuerResolutionFailed(
                webID,
                "profile request returned HTTP ${response.statusCode}",
            )
        }
        val graph = Turtle.parse(response.bodyText, base = documentURL(response.url))
        return graph
            .iriObjects(Term.IRI(webID.toString()), Term.IRI(OIDC_ISSUER_PREDICATE))
            .mapNotNull { runCatching { URI(it) }.getOrNull() }
    }

    /**
     * Resolve a login input to a single issuer, mirroring the reference
     * semantics:
     * - RDF with one issuer → that issuer (input was a WebID).
     * - RDF with several issuers → [chooseIssuer], or [SolidOidcException.AmbiguousIssuer].
     * - RDF with none but a fragment (`#me`) → clearly a WebID, so [SolidOidcException.NoOidcIssuer].
     * - Anything else → treat the input as the issuer; OIDC discovery validates it.
     */
    public fun resolve(
        input: URI,
        httpClient: HttpClient,
        chooseIssuer: ((List<URI>) -> URI)? = null,
    ): Resolution {
        val advertised = runCatching { issuers(input, httpClient) }.getOrNull()
        if (advertised != null) {
            when {
                advertised.size == 1 -> return Resolution(advertised[0], input)
                advertised.size > 1 -> {
                    val chooser = chooseIssuer
                        ?: throw SolidOidcException.AmbiguousIssuer(input, advertised)
                    return Resolution(chooser(advertised), input)
                }
                input.fragment != null -> throw SolidOidcException.NoOidcIssuer(input)
            }
        }
        return Resolution(input, null)
    }

    private fun documentURL(url: String): String {
        val uri = URI(url)
        return URI(uri.scheme, uri.authority, uri.path, uri.query, null).toString()
    }
}
