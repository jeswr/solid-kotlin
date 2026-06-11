package dev.jeswr.solid.rdf

/**
 * An in-memory RDF graph: a set of triples with subject/predicate/object
 * pattern matching.
 *
 * `Graph` is an immutable value type — [insert]/[remove]/etc. return a new
 * graph and leave the receiver untouched, so copies are independent. Use
 * [MutableGraph] (or the [GraphBox] in solid-object) when you need in-place
 * mutation.
 */
public class Graph private constructor(private val storage: Set<Triple>) : Iterable<Triple> {

    public constructor() : this(emptySet())

    public constructor(triples: Iterable<Triple>) : this(triples.toSet())

    /** The number of distinct triples. */
    public val size: Int get() = storage.size

    /** True when the graph holds no triples. */
    public val isEmpty: Boolean get() = storage.isEmpty()

    /** Whether the graph contains exactly this triple. */
    public operator fun contains(triple: Triple): Boolean = triple in storage

    /** Return a new graph with [triple] inserted (idempotent). */
    public fun insert(triple: Triple): Graph =
        if (triple in storage) this else Graph(storage + triple)

    /** Return a new graph with [triples] inserted. */
    public fun insertAll(triples: Iterable<Triple>): Graph = Graph(storage + triples)

    /** Return a new graph with [triple] removed if present. */
    public fun remove(triple: Triple): Graph =
        if (triple in storage) Graph(storage - triple) else this

    /** Return a new graph with every triple matching the pattern removed. */
    public fun removeMatching(
        subject: Term? = null,
        predicate: Term? = null,
        `object`: Term? = null,
    ): Graph = Graph(storage.filterTo(LinkedHashSet()) { !matches(it, subject, predicate, `object`) })

    /** Every triple matching the pattern (null = wildcard). */
    public fun triples(
        subject: Term? = null,
        predicate: Term? = null,
        `object`: Term? = null,
    ): List<Triple> = storage.filter { matches(it, subject, predicate, `object`) }

    /** The objects of every `subject predicate ?o` triple. */
    public fun objects(subject: Term, predicate: Term): List<Term> =
        triples(subject = subject, predicate = predicate).map { it.`object` }

    /** The subjects of every `?s predicate object` triple. */
    public fun subjects(predicate: Term, `object`: Term): List<Term> =
        triples(predicate = predicate, `object` = `object`).map { it.subject }

    /**
     * The first object of `subject predicate ?o`, or null. Order is
     * unspecified when several match.
     */
    public fun firstObject(subject: Term, predicate: Term): Term? =
        storage.firstOrNull { matches(it, subject, predicate, null) }?.`object`

    /**
     * The IRI objects of `subject predicate ?o`, as strings, sorted for
     * deterministic output.
     */
    public fun iriObjects(subject: Term, predicate: Term): List<String> =
        objects(subject, predicate).mapNotNull { it.iriValue }.sorted()

    /**
     * Follow an RDF collection (`rdf:first`/`rdf:rest`) from [head] to
     * `rdf:nil`, returning the member terms in order. Returns what was gathered
     * so far if the list is malformed (missing rest).
     */
    public fun list(head: Term): List<Term> {
        val items = mutableListOf<Term>()
        var node = head
        val seen = mutableSetOf<Term>()
        while (node != Term.IRI(RDF.NIL) && seen.add(node)) {
            val first = firstObject(node, Term.IRI(RDF.FIRST)) ?: break
            val rest = firstObject(node, Term.IRI(RDF.REST)) ?: break
            items.add(first)
            node = rest
        }
        return items
    }

    override fun iterator(): Iterator<Triple> = storage.iterator()

    override fun equals(other: Any?): Boolean = other is Graph && other.storage == storage

    override fun hashCode(): Int = storage.hashCode()

    public companion object {
        public fun of(vararg triples: Triple): Graph = Graph(triples.toSet())

        private fun matches(
            triple: Triple,
            subject: Term?,
            predicate: Term?,
            `object`: Term?,
        ): Boolean =
            (subject == null || triple.subject == subject) &&
                (predicate == null || triple.predicate == predicate) &&
                (`object` == null || triple.`object` == `object`)
    }
}
