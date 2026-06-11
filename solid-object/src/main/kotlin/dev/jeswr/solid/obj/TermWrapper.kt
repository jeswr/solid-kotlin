package dev.jeswr.solid.obj

import dev.jeswr.solid.rdf.Term

/**
 * The base class for typed RDF mapping classes — the Kotlin analogue of
 * `@rdfjs/wrapper`'s `TermWrapper`.
 *
 * Subclass it and expose domain properties either through the explicit
 * accessor functions ([OptionalFrom]/[RequiredFrom]/[SetFrom] for reads,
 * [OptionalAs]/[RequiredAs] for writes — parity with the Swift SDK) or, more
 * idiomatically, through Kotlin property delegates ([optional], [required],
 * [set]). The wrapper holds only a [term] (the subject it describes) and a
 * reference to the shared [GraphBox]; it stores no copied state, so two
 * wrappers over the same term and box always agree.
 *
 * ```kotlin
 * class Person(term: Term, graph: GraphBox) : TermWrapper(term, graph) {
 *     // delegate style:
 *     val name: String? by optional(FOAF.NAME, LiteralAs.string)
 *     var email: String? by optionalRW(VCARD.HAS_EMAIL, LiteralAs.string, LiteralFrom.string)
 *     // explicit-accessor style (Swift parity):
 *     fun requiredName(): String =
 *         RequiredFrom.subjectPredicate(this, FOAF.NAME, LiteralAs.string)
 * }
 * ```
 *
 * Subclasses must expose a `(Term, GraphBox)` constructor so generic code
 * ([TermAs.instance], [DatasetWrapper.instances]) can build any subclass.
 */
public abstract class TermWrapper(
    /**
     * The subject this wrapper describes. Must be an IRI or blank node for the
     * read/write accessors to address triples.
     */
    public val term: Term,
    /** The shared, mutable graph this wrapper reads from and writes to. */
    public val graph: GraphBox,
) {
    /** Convenience for the overwhelmingly common IRI-subject case. */
    public constructor(iri: String, graph: GraphBox) : this(Term.IRI(iri), graph)

    /** The wrapped term's IRI, when it is one (most subjects are). */
    public val iri: String? get() = term.iriValue
}
