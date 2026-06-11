package dev.jeswr.solid.rdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TurtleParserTest {

    @Test
    fun parsesWebIDProfile() {
        val turtle = """
            @prefix foaf: <http://xmlns.com/foaf/0.1/>.
            @prefix solid: <http://www.w3.org/ns/solid/terms#>.
            @prefix pim: <http://www.w3.org/ns/pim/space#>.

            <card> a foaf:PersonalProfileDocument; foaf:primaryTopic <card#me>.
            <card#me> a foaf:Person;
                foaf:name "Alice Test"@en;
                solid:oidcIssuer <https://idp.example/>;
                pim:storage <https://pod.example/alice/>.
        """.trimIndent()
        val graph = Turtle.parse(turtle, base = "https://pod.example/alice/profile/")
        val me = Term.iri("https://pod.example/alice/profile/card#me")
        assertTrue(
            graph.contains(
                Triple(me, Term.iri(RDF.TYPE), Term.iri("http://xmlns.com/foaf/0.1/Person")),
            ),
        )
        assertEquals(
            Term.literal(Literal.lang("Alice Test", "en")),
            graph.firstObject(me, Term.iri("http://xmlns.com/foaf/0.1/name")),
        )
        assertEquals(
            listOf("https://pod.example/alice/"),
            graph.iriObjects(me, Term.iri("http://www.w3.org/ns/pim/space#storage")),
        )
    }

    @Test
    fun parsesSparqlStyleDirectives() {
        val graph = Turtle.parse(
            """
            PREFIX ex: <https://example.org/>
            BASE <https://base.example/>
            ex:s ex:p <relative>.
            """.trimIndent(),
        )
        assertTrue(
            graph.contains(
                Triple("https://example.org/s", "https://example.org/p", Term.iri("https://base.example/relative")),
            ),
        )
    }

    @Test
    fun parsesLiterals() {
        val graph = Turtle.parse(
            """
            @prefix ex: <https://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:s ex:plain "hello";
                 ex:lang "bonjour"@fr-CA;
                 ex:typed "2024-01-01T00:00:00Z"^^xsd:dateTime;
                 ex:int 42;
                 ex:dec 3.14;
                 ex:dbl 1.0e6;
                 ex:neg -7;
                 ex:bool true;
                 ex:quoted "she said \"hi\"\n";
                 ex:long ${"\"\"\""}multi
            line "quoted" text${"\"\"\""};
                 ex:single 'apostrophes';
                 ex:unicode "café".
            """.trimIndent(),
        )
        val s = Term.iri("https://example.org/s")
        fun obj(p: String): Term? = graph.firstObject(s, Term.iri("https://example.org/$p"))
        assertEquals(Term.string("hello"), obj("plain"))
        assertEquals(Term.literal(Literal.lang("bonjour", "fr-ca")), obj("lang"))
        assertEquals(Term.literal(Literal("2024-01-01T00:00:00Z", XSD.DATE_TIME)), obj("typed"))
        assertEquals(Term.literal(Literal("42", XSD.INTEGER)), obj("int"))
        assertEquals(Term.literal(Literal("3.14", XSD.DECIMAL)), obj("dec"))
        assertEquals(Term.literal(Literal("1.0e6", XSD.DOUBLE)), obj("dbl"))
        assertEquals(Term.literal(Literal("-7", XSD.INTEGER)), obj("neg"))
        assertEquals(Term.literal(Literal.boolean(true)), obj("bool"))
        assertEquals(Term.string("she said \"hi\"\n"), obj("quoted"))
        assertEquals(Term.string("multi\nline \"quoted\" text"), obj("long"))
        assertEquals(Term.string("apostrophes"), obj("single"))
        assertEquals(Term.string("café"), obj("unicode"))
    }

    @Test
    fun parsesBlankNodes() {
        val graph = Turtle.parse(
            """
            @prefix ex: <https://example.org/> .
            _:b1 ex:p ex:o.
            ex:s ex:knows [ ex:name "Anon"; ex:age 9 ], _:b1.
            [] ex:standalone true.
            """.trimIndent(),
        )
        val knows = graph.objects(
            Term.iri("https://example.org/s"),
            Term.iri("https://example.org/knows"),
        )
        assertEquals(2, knows.size)
        val anon = knows.first {
            graph.objects(it, Term.iri("https://example.org/name")).isNotEmpty()
        }
        assertEquals(
            Term.literal(Literal.integer(9)),
            graph.firstObject(anon, Term.iri("https://example.org/age")),
        )
        assertTrue(
            graph.contains(
                Triple(Term.blankNode("b1"), Term.iri("https://example.org/p"), Term.iri("https://example.org/o")),
            ),
        )
    }

    @Test
    fun parsesCollections() {
        val graph = Turtle.parse(
            """
            @prefix ex: <https://example.org/> .
            ex:s ex:list (ex:a "two" 3) ; ex:empty ().
            """.trimIndent(),
        )
        val s = Term.iri("https://example.org/s")
        val head = graph.firstObject(s, Term.iri("https://example.org/list"))!!
        assertEquals(
            listOf(
                Term.iri("https://example.org/a"),
                Term.string("two"),
                Term.literal(Literal.integer(3)),
            ),
            graph.list(head),
        )
        assertEquals(
            Term.iri(RDF.NIL),
            graph.firstObject(s, Term.iri("https://example.org/empty")),
        )
    }

    @Test
    fun parsesCommentsAndSemicolonRuns() {
        val graph = Turtle.parse(
            """
            # leading comment
            @prefix ex: <https://example.org/> . # trailing comment
            ex:s ex:p ex:o ; ; .
            """.trimIndent(),
        )
        assertEquals(1, graph.size)
    }

    @Test
    fun rejectsUndefinedPrefix() {
        assertThrows<TurtleParseException> {
            Turtle.parse("undefined:s <https://example.org/p> 1 .")
        }
    }

    @Test
    fun rejectsUnterminatedIRI() {
        assertThrows<TurtleParseException> {
            Turtle.parse("<https://example.org/s <https://example.org/p> 1 .")
        }
    }

    @Test
    fun errorCarriesPosition() {
        val ex = assertThrows<TurtleParseException> {
            Turtle.parse("@prefix ex: <https://example.org/> .\nex:s ex:p @bad .")
        }
        assertEquals(2, ex.line)
    }

    @Test
    fun relativeIRIResolution() {
        val base = "https://pod.example/alice/profile/card"
        val graph = Turtle.parse(
            """
            <> <#frag> <.>.
            <../> <other> </root>.
            """.trimIndent(),
            base = base,
        )
        assertTrue(
            graph.contains(
                Triple(
                    "https://pod.example/alice/profile/card",
                    "https://pod.example/alice/profile/card#frag",
                    Term.iri("https://pod.example/alice/profile/"),
                ),
            ),
        )
        assertTrue(
            graph.contains(
                Triple(
                    "https://pod.example/alice/",
                    "https://pod.example/alice/profile/other",
                    Term.iri("https://pod.example/root"),
                ),
            ),
        )
    }
}
