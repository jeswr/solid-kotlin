package dev.jeswr.solid.obj

import dev.jeswr.solid.rdf.Graph
import dev.jeswr.solid.rdf.RDF
import dev.jeswr.solid.rdf.Term

/**
 * A typed view over a whole [GraphBox], for projecting *collections* of
 * resources — the Kotlin analogue of `@rdfjs/wrapper`'s `DatasetWrapper`.
 *
 * ```kotlin
 * val people = DatasetWrapper(box).instances(FOAF.PERSON, ::Person)
 * for (person in people) println(person.name ?: person.iri ?: "?")
 * ```
 */
public open class DatasetWrapper(
    /** The shared graph this view reads from and writes to. */
    public val graph: GraphBox,
) {
    public constructor(graph: Graph) : this(GraphBox(graph))

    /**
     * Every resource typed `rdf:type <classIRI>`, projected as [T]. Blank-node
     * and IRI subjects are both included; literals cannot be subjects.
     */
    public fun <T : TermWrapper> instances(classIRI: String, factory: (Term, GraphBox) -> T): List<T> =
        graph.graph
            .subjects(Term.IRI(RDF.TYPE), Term.IRI(classIRI))
            .filter { it.isResource }
            .map { factory(it, graph) }

    /**
     * Every object of `subject predicate ?o` that is a resource, projected as
     * [T] (literals are skipped).
     */
    public fun <T : TermWrapper> objects(
        subject: Term,
        predicate: String,
        factory: (Term, GraphBox) -> T,
    ): List<T> =
        graph.graph
            .objects(subject, Term.IRI(predicate))
            .filter { it.isResource }
            .map { factory(it, graph) }

    /**
     * Every subject of `?s predicate object` that is a resource, projected as
     * [T].
     */
    public fun <T : TermWrapper> subjects(
        predicate: String,
        `object`: Term,
        factory: (Term, GraphBox) -> T,
    ): List<T> =
        graph.graph
            .subjects(Term.IRI(predicate), `object`)
            .filter { it.isResource }
            .map { factory(it, graph) }
}
