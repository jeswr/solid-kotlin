package dev.jeswr.solid.pod

import dev.jeswr.solid.obj.GraphBox
import dev.jeswr.solid.obj.ProfileAgent
import dev.jeswr.solid.rdf.Graph
import dev.jeswr.solid.rdf.Term
import java.net.URI

/**
 * Typed convenience over a WebID profile document, backed by solid-object's
 * [ProfileAgent] (fallback chains for name/photo/email).
 */
public class WebIDProfile(
    public val webID: URI,
    /** The full profile graph, for anything beyond the conveniences. */
    public val graph: Graph,
) {
    private val agent: ProfileAgent = ProfileAgent(webID.toString(), GraphBox(graph))

    /** `foaf:name`, preferring a literal without a language tag, then any. */
    public val name: String?
        get() {
            val names = graph.objects(Term.IRI(webID.toString()), Term.IRI(Vocab.FOAF_NAME))
                .mapNotNull { it.literalValue }
            return (names.firstOrNull { it.language == null } ?: names.firstOrNull())?.lexicalForm
                ?: agent.name
        }

    /** `pim:storage` roots advertised on the profile (may be empty). */
    public val storages: List<URI>
        get() = graph.iriObjects(Term.IRI(webID.toString()), Term.IRI(Vocab.PIM_STORAGE))
            .mapNotNull { runCatching { URI(it) }.getOrNull() }

    /** `solid:oidcIssuer` entries. */
    public val oidcIssuers: List<URI>
        get() = graph.iriObjects(Term.IRI(webID.toString()), Term.IRI(Vocab.SOLID_OIDC_ISSUER))
            .mapNotNull { runCatching { URI(it) }.getOrNull() }
}

/** Dereference a WebID and wrap its profile document. */
public fun SolidPodClient.profile(webID: URI): WebIDProfile {
    val resource = readResource(webID)
    if (!resource.exists) throw PodException.NotFound(webID)
    return WebIDProfile(webID, resource.graph)
}
