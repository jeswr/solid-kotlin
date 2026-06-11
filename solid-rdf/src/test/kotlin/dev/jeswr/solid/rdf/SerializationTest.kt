package dev.jeswr.solid.rdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SerializationTest {

    private val sample = Graph.of(
        Triple("https://example.org/s", RDF.TYPE, Term.iri("https://example.org/Thing")),
        Triple("https://example.org/s", "https://example.org/name", Term.string("A \"quoted\" name\n")),
        Triple("https://example.org/s", "https://example.org/label", Term.literal(Literal.lang("étiquette", "fr"))),
        Triple("https://example.org/s", "https://example.org/count", Term.literal(Literal.integer(5))),
        Triple(Term.blankNode("x"), Term.iri("https://example.org/p"), Term.blankNode("y")),
    )

    private val prefixes = mapOf("ex" to "https://example.org/")

    @Test
    fun turtleRoundTrips() {
        val turtle = Turtle.serialize(sample, prefixes)
        val reparsed = Turtle.parse(turtle)
        assertTrue(equalUpToBlankRenaming(sample, reparsed))
    }

    @Test
    fun turtleUsesPrefixesAndA() {
        val turtle = Turtle.serialize(sample, prefixes)
        assertTrue(turtle.contains("@prefix ex: <https://example.org/> ."))
        assertTrue(turtle.contains("ex:s a ex:Thing"))
        assertFalse(turtle.contains("<https://example.org/s>"))
    }

    @Test
    fun turtleOutputIsDeterministic() {
        val a = Turtle.serialize(sample, prefixes)
        val b = Turtle.serialize(Graph(sample.shuffled()), prefixes)
        assertEquals(a, b)
    }

    @Test
    fun nTriplesRoundTrips() {
        val nt = NTriples.serialize(sample)
        val reparsed = NTriples.parse(nt)
        assertTrue(equalUpToBlankRenaming(sample, reparsed))
    }

    @Test
    fun collectionRoundTrips() {
        val original = Turtle.parse(
            """
            @prefix ex: <https://example.org/> .
            ex:s ex:list (1 2 3).
            """.trimIndent(),
        )
        val reparsed = Turtle.parse(Turtle.serialize(original))
        assertTrue(equalUpToBlankRenaming(original, reparsed))
        val head = reparsed.firstObject(
            Term.iri("https://example.org/s"),
            Term.iri("https://example.org/list"),
        )!!
        assertEquals(3, reparsed.list(head).size)
    }

    private fun equalUpToBlankRenaming(a: Graph, b: Graph): Boolean {
        fun ground(g: Graph): Set<Triple> =
            g.filter { it.subject.isGround && it.`object`.isGround }.toSet()
        return ground(a) == ground(b) && a.size == b.size
    }

    private val Term.isGround: Boolean
        get() = this !is Term.BlankNode
}
