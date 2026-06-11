package dev.jeswr.solid.obj

import dev.jeswr.solid.rdf.Term
import java.net.URI

/**
 * A typed view over a WebID profile — the showcase domain wrapper, and a
 * faithful Kotlin counterpart to `@solid/object`'s `Agent`.
 *
 * It demonstrates the pattern the rest of your app should follow: every
 * human-facing field reads through a **fallback chain** because, as the Solid
 * guide stresses, no single predicate is guaranteed across servers and profile
 * editors.
 *
 * ```kotlin
 * val me = ProfileAgent(webID, GraphBox(profileGraph))
 * println(me.displayName)          // never empty — falls back to the WebID
 * println(me.storageUrls.values)   // pod roots, for deriving write targets
 * ```
 */
public class ProfileAgent(term: Term, graph: GraphBox) : TermWrapper(term, graph) {
    public constructor(iri: String, graph: GraphBox) : this(Term.IRI(iri), graph)

    /**
     * `foaf:name` → `schema:name` → `vcard:fn` → `as:name` → `rdfs:label`. null
     * only when none are present (use [displayName] for a guaranteed value).
     */
    public val name: String? by optionalChain(
        listOf(FOAF.NAME, SCHEMA.NAME, VCARD.FN, AS.NAME, RDFS.LABEL),
        LiteralAs.string,
    )

    /**
     * A name guaranteed non-empty: [name] if present, otherwise the WebID
     * itself — the final fallback the guide specifies for rendering.
     */
    public val displayName: String
        get() = name ?: iri ?: term.toString()

    /**
     * Set the canonical name (`foaf:name`). Leaves any alternative name
     * predicates untouched.
     */
    public fun setName(newName: String?) {
        OptionalAs.`object`(this, FOAF.NAME, newName, LiteralFrom.string)
    }

    /**
     * Photo IRI: `vcard:hasPhoto` → `as:image` → `foaf:img` → `schema:image`
     * → `foaf:depiction`.
     */
    public val photo: URI? by optionalChain(
        listOf(VCARD.HAS_PHOTO, AS.IMAGE, FOAF.IMG, SCHEMA.IMAGE, FOAF.DEPICTION),
        IRIAs.uri,
    )

    /**
     * Email address: `vcard:hasEmail` → `schema:email` → `foaf:mbox`, with the
     * `mailto:` scheme stripped. Handles `vcard:hasEmail` pointing either
     * directly at a value or at an intermediate node carrying `vcard:value`.
     */
    public val email: String?
        get() {
            for (predicate in listOf(VCARD.HAS_EMAIL, SCHEMA.EMAIL, FOAF.MBOX)) {
                val obj = graph.graph.firstObject(term, Term.IRI(predicate)) ?: continue
                if (obj.isResource) {
                    val nested = graph.graph.firstObject(obj, Term.IRI(VCARD.VALUE))
                    if (nested != null) {
                        emailString(nested)?.let { return it }
                    }
                }
                emailString(obj)?.let { return it }
            }
            return null
        }

    /**
     * The Solid-OIDC issuer (`solid:oidcIssuer`) declared in this profile. Trust
     * this **only** when read from the WebID document itself.
     */
    public val oidcIssuer: URI? by optional(SOLID.OIDC_ISSUER, IRIAs.uri)

    /**
     * Pod storage roots (`pim:storage`) — the only sanctioned storage-discovery
     * mechanism. A profile may declare several; never pick one silently.
     */
    public val storageUrls: WrappedSet<URI> by set(PIM.STORAGE, IRIAs.uri, IRIFrom.uri)

    /** People this agent `foaf:knows`, each as a further [ProfileAgent]. */
    public val knows: WrappedSet<ProfileAgent> by set(
        FOAF.KNOWS,
        TermAs.instance(::ProfileAgent),
        TermFrom.instance(),
    )

    /**
     * Profile-expansion links (`rdfs:seeAlso`, `pim:preferencesFile`,
     * `owl:sameAs`) whose subject is this WebID — fetch each into the same graph
     * to assemble the full profile (follow ≥2 hops with cycle detection).
     */
    public val seeAlso: List<URI>
        get() = listOf(RDFS.SEE_ALSO, PIM.PREFERENCES_FILE, OWL.SAME_AS).flatMap { predicate ->
            graph.graph.objects(term, Term.IRI(predicate))
                .mapNotNull { it.iriValue }
                .mapNotNull { runCatching { URI(it) }.getOrNull() }
        }

    private fun emailString(term: Term): String? = when (term) {
        is Term.IRI -> term.value.removePrefix("mailto:")
        is Term.LiteralTerm -> term.literal.lexicalForm.removePrefix("mailto:")
        is Term.BlankNode -> null
    }
}
