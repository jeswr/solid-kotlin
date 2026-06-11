package dev.jeswr.solid.obj

import dev.jeswr.solid.rdf.Graph
import dev.jeswr.solid.rdf.Triple

/**
 * A reference-typed, mutable holder around an immutable [Graph].
 *
 * [TermWrapper] instances read from and write to a *shared* graph: assigning
 * `person.name = "Alice"` must be visible to every other wrapper viewing the
 * same data, exactly as `@rdfjs/wrapper` mutates a single `DatasetCore` in
 * place. Kotlin's [Graph] is immutable, so wrappers hold a reference to a
 * `GraphBox` and replace `box.graph` through it.
 *
 * `GraphBox` is a single-domain working buffer (the Kotlin analogue of an
 * `n3.Store`). Build and mutate it on one thread, then serialise the resulting
 * [Graph] and hand the *string* — never the box — across threads. This matches
 * the pod read–modify–write cycle: fetch → wrap → mutate → serialise →
 * conditional `PUT`.
 */
public class GraphBox(graph: Graph = Graph()) {
    /**
     * The underlying graph. Mutating it through a wrapper is the whole point;
     * reading it back gives you the serialisable result.
     */
    public var graph: Graph = graph

    internal fun insert(triple: Triple) {
        graph = graph.insert(triple)
    }

    internal fun remove(triple: Triple) {
        graph = graph.remove(triple)
    }

    internal fun removeMatching(
        subject: dev.jeswr.solid.rdf.Term? = null,
        predicate: dev.jeswr.solid.rdf.Term? = null,
        `object`: dev.jeswr.solid.rdf.Term? = null,
    ) {
        graph = graph.removeMatching(subject, predicate, `object`)
    }
}
