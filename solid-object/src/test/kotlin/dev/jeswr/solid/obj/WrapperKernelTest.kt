package dev.jeswr.solid.obj

import dev.jeswr.solid.rdf.RDF
import dev.jeswr.solid.rdf.Term
import dev.jeswr.solid.rdf.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.time.Instant
import kotlin.math.abs

private const val EX = "https://example.org/"

/**
 * A minimal domain wrapper exercising every accessor family. `name` uses the
 * idiomatic delegate style; the rest use the explicit Swift-parity accessors —
 * both must work.
 */
private class Person(term: Term, graph: GraphBox) : TermWrapper(term, graph) {
    constructor(iri: String, graph: GraphBox) : this(Term.IRI(iri), graph)

    // delegate style
    var name: String? by optionalRW(EX + "name", LiteralAs.string, LiteralFrom.string)

    // explicit-accessor style
    var age: Int?
        get() = OptionalFrom.subjectPredicate(this, EX + "age", LiteralAs.int)
        set(value) = OptionalAs.`object`(this, EX + "age", value, LiteralFrom.integerInt)

    var homepage: URI?
        get() = OptionalFrom.subjectPredicate(this, EX + "homepage", IRIAs.uri)
        set(value) = OptionalAs.`object`(this, EX + "homepage", value, IRIFrom.uri)

    fun requiredName(): String = RequiredFrom.subjectPredicate(this, EX + "name", LiteralAs.string)

    var mum: Person?
        get() = OptionalFrom.subjectPredicate(this, EX + "mum", TermAs.instance(::Person))
        set(value) = OptionalAs.`object`(this, EX + "mum", value, TermFrom.instance())

    val knows: WrappedSet<Person> by set(EX + "knows", TermAs.instance(::Person), TermFrom.instance())
}

class WrapperKernelTest {

    private fun person(iri: String = EX + "alice"): Pair<Person, GraphBox> {
        val box = GraphBox()
        return Person(iri, box) to box
    }

    @Test
    fun writesThenReadsLiteral() {
        val (alice, box) = person()
        alice.name = "Alice"
        assertEquals("Alice", alice.name)
        assertEquals(1, box.graph.triples(predicate = Term.IRI(EX + "name")).size)
    }

    @Test
    fun setReplacesExistingObject() {
        val (alice, box) = person()
        alice.name = "Alice"
        alice.name = "Alicia"
        assertEquals("Alicia", alice.name)
        assertEquals(1, box.graph.triples(predicate = Term.IRI(EX + "name")).size)
    }

    @Test
    fun nilClearsProperty() {
        val (alice, _) = person()
        alice.name = "Alice"
        alice.name = null
        assertNull(alice.name)
    }

    @Test
    fun typedScalarsRoundTrip() {
        val (alice, _) = person()
        alice.age = 42
        alice.homepage = URI("https://alice.example/")
        assertEquals(42, alice.age)
        assertEquals("https://alice.example/", alice.homepage?.toString())
    }

    @Test
    fun requiredThrowsWhenMissing() {
        val (alice, _) = person()
        assertThrows<WrapperException> { alice.requiredName() }
    }

    @Test
    fun requiredReturnsWhenPresent() {
        val (alice, _) = person()
        alice.name = "Alice"
        assertEquals("Alice", alice.requiredName())
    }

    @Test
    fun optionalIsLenientOnUndecodableObject() {
        val (alice, box) = person()
        box.graph = box.graph.insert(
            dev.jeswr.solid.rdf.Triple(alice.term, Term.IRI(EX + "age"), Term.IRI(EX + "not-a-number")),
        )
        assertNull(alice.age)
    }

    @Test
    fun navigatesNestedWrapper() {
        val (alice, box) = person()
        val bob = Person(EX + "bob", box)
        bob.name = "Bob"
        alice.mum = bob
        assertEquals("Bob", alice.mum?.name)
        assertEquals(EX + "bob", alice.mum?.iri)
    }

    @Test
    fun liveSetAddsRemovesAndReads() {
        val (alice, box) = person()
        val bob = Person(EX + "bob", box)
        val carol = Person(EX + "carol", box)
        bob.name = "Bob"; carol.name = "Carol"
        alice.knows.add(bob)
        alice.knows.add(carol)
        assertEquals(2, alice.knows.size)
        assertEquals(setOf(EX + "bob", EX + "carol"), alice.knows.map { it.iri }.toSet())
        alice.knows.remove(bob)
        assertEquals(1, alice.knows.size)
        assertEquals("Carol", alice.knows.first?.name)
    }

    @Test
    fun datasetWrapperProjectsInstances() {
        val box = GraphBox()
        for (id in listOf("a", "b")) {
            box.graph = box.graph
                .insert(dev.jeswr.solid.rdf.Triple(Term.iri(EX + id), Term.iri(RDF.TYPE), Term.iri(EX + "Person")))
                .insert(dev.jeswr.solid.rdf.Triple(Term.iri(EX + id), Term.iri(EX + "name"), Term.string(id.uppercase())))
        }
        val people = DatasetWrapper(box).instances(EX + "Person", ::Person)
        assertEquals(setOf("A", "B"), people.mapNotNull { it.name }.toSet())
    }

    @Test
    fun dateRoundTripsAsXsdDate() {
        // 2026-06-11 UTC midnight.
        val date = Instant.ofEpochSecond(1_781_136_000)
        val box = GraphBox()
        val alice = Person(EX + "alice", box)
        RequiredAs.`object`(alice, EX + "dob", date, LiteralFrom.date)
        val obj = box.graph.firstObject(alice.term, Term.IRI(EX + "dob"))
        val literal = (obj as Term.LiteralTerm).literal
        // Correct xsd:date — datatype AND a date-only lexical (the JS bug emits dateTime here).
        assertEquals(XSD.DATE, literal.datatypeIRI)
        assertEquals("2026-06-11", literal.lexicalForm)
        assertNotNull(runCatching { LiteralAs.instant.decode(obj, box) }.getOrNull())
    }

    @Test
    fun dateTimeRoundTrips() {
        val date = Instant.ofEpochSecond(1_781_136_000)
        val box = GraphBox()
        val alice = Person(EX + "alice", box)
        RequiredAs.`object`(alice, EX + "seen", date, LiteralFrom.dateTime)
        val value = RequiredFrom.subjectPredicate(alice, EX + "seen", LiteralAs.instant)
        assertTrue(abs(value.epochSecond - date.epochSecond) < 1)
    }
}
