package dev.jeswr.solid.obj

import dev.jeswr.solid.rdf.Term
import dev.jeswr.solid.rdf.Triple

// The accessors come in two suffix families that run in OPPOSITE directions,
// mirroring `@rdfjs/wrapper`:
//
//   • property cardinality — read with `…From`, write with `…As`
//       RequiredFrom / OptionalFrom / SetFrom        (dataset → value)
//       RequiredAs   / OptionalAs                    (value → dataset)
//   • value mapping — read with `…As`, write with `…From`
//       LiteralAs / IRIAs / TermAs                   (Term → value)
//       LiteralFrom / IRIFrom / TermFrom             (value → Term)
//
// So a getter is `…From.subjectPredicate(this, iri, …As)` and a setter is
// `…As.object(this, iri, value, …From)`.

/**
 * Reads the single required object of `subject predicate ?o`. Throws
 * [WrapperException.MissingRequired] when absent and propagates a decode
 * failure (use for properties your model guarantees).
 */
public object RequiredFrom {
    public fun <V> subjectPredicate(
        wrapper: TermWrapper,
        predicate: String,
        decoder: TermDecoder<V>,
    ): V {
        val obj = wrapper.graph.graph.firstObject(wrapper.term, Term.IRI(predicate))
            ?: throw WrapperException.MissingRequired(wrapper.term, predicate)
        return decoder.decode(obj, wrapper.graph)
    }
}

/**
 * Reads at most one object of `subject predicate ?o`, leniently: it returns the
 * first object that decodes successfully, or null if there is none or none
 * decode. The workhorse for real-world pod data.
 */
public object OptionalFrom {
    public fun <V> subjectPredicate(
        wrapper: TermWrapper,
        predicate: String,
        decoder: TermDecoder<V>,
    ): V? {
        for (obj in wrapper.graph.graph.objects(wrapper.term, Term.IRI(predicate))) {
            runCatching { return decoder.decode(obj, wrapper.graph) }
        }
        return null
    }

    /**
     * A fallback chain: try each predicate in order and return the first that
     * yields a decodable object. Encodes the guide's "read predicates with
     * fallback chains — no single predicate is guaranteed" rule
     * (`foaf:name` → `schema:name` → `vcard:fn` → …).
     */
    public fun <V> firstSubjectPredicate(
        wrapper: TermWrapper,
        predicates: List<String>,
        decoder: TermDecoder<V>,
    ): V? {
        for (predicate in predicates) {
            subjectPredicate(wrapper, predicate, decoder)?.let { return it }
        }
        return null
    }
}

/**
 * Exposes every object of `subject predicate ?o` as a live, mutable
 * [WrappedSet]. Reads decode leniently; mutations write straight through.
 */
public object SetFrom {
    public fun <V> subjectPredicate(
        wrapper: TermWrapper,
        predicate: String,
        decoder: TermDecoder<V>,
        encoder: TermEncoder<V>,
    ): WrappedSet<V> = WrappedSet(wrapper, predicate, decoder, encoder)
}

/**
 * Sets the object of `subject predicate ?o`, replacing any existing objects for
 * that predicate (functional-property semantics).
 */
public object RequiredAs {
    public fun <V> `object`(
        wrapper: TermWrapper,
        predicate: String,
        value: V,
        encoder: TermEncoder<V>,
    ) {
        val obj = encoder.encode(value, wrapper.graph)
        wrapper.graph.removeMatching(subject = wrapper.term, predicate = Term.IRI(predicate))
        wrapper.graph.insert(Triple(wrapper.term, Term.IRI(predicate), obj))
    }
}

/**
 * Sets or clears the object of `subject predicate ?o`. A non-null value
 * replaces any existing objects; null removes them all.
 */
public object OptionalAs {
    public fun <V> `object`(
        wrapper: TermWrapper,
        predicate: String,
        value: V?,
        encoder: TermEncoder<V>,
    ) {
        wrapper.graph.removeMatching(subject = wrapper.term, predicate = Term.IRI(predicate))
        if (value == null) return
        wrapper.graph.insert(Triple(wrapper.term, Term.IRI(predicate), encoder.encode(value, wrapper.graph)))
    }
}

/**
 * A live view over the objects of `subject predicate ?o`, presented as a
 * mutable collection. It holds no snapshot: every read re-queries the shared
 * graph, and [add]/[remove] write straight through — the Kotlin analogue of the
 * live `Set` `@rdfjs/wrapper`'s `SetFrom` returns.
 *
 * Iteration and [values] are lenient: objects that fail to decode with the
 * configured decoder are skipped.
 */
public class WrappedSet<V> internal constructor(
    private val wrapper: TermWrapper,
    private val predicate: String,
    private val decoder: TermDecoder<V>,
    private val encoder: TermEncoder<V>,
) : Iterable<V> {

    private val predicateTerm get() = Term.IRI(predicate)

    /** The current members, decoded (undecodable objects skipped). */
    public val values: List<V>
        get() = wrapper.graph.graph.objects(wrapper.term, predicateTerm)
            .mapNotNull { obj -> runCatching { decoder.decode(obj, wrapper.graph) }.getOrNull() }

    /** The number of distinct object triples for this predicate. */
    public val size: Int
        get() = wrapper.graph.graph.triples(subject = wrapper.term, predicate = predicateTerm).size

    /** Whether the predicate has any object at all. */
    public val isEmpty: Boolean get() = size == 0

    /** The first decodable member, or null. */
    public val first: V? get() = values.firstOrNull()

    /** Add [value] as an object (idempotent — the underlying graph is a set). */
    public fun add(value: V) {
        wrapper.graph.insert(Triple(wrapper.term, predicateTerm, encoder.encode(value, wrapper.graph)))
    }

    /** Remove the triple whose object equals the encoding of [value], if present. */
    public fun remove(value: V) {
        wrapper.graph.removeMatching(
            subject = wrapper.term,
            predicate = predicateTerm,
            `object` = encoder.encode(value, wrapper.graph),
        )
    }

    /** Remove every object for this predicate. */
    public fun removeAll() {
        wrapper.graph.removeMatching(subject = wrapper.term, predicate = predicateTerm)
    }

    /** Replace the whole set with [newValues]. */
    public fun replace(newValues: Iterable<V>) {
        removeAll()
        for (value in newValues) add(value)
    }

    override fun iterator(): Iterator<V> = values.iterator()
}
