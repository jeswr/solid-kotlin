package dev.jeswr.solid.rdf

/**
 * N-Triples parsing and serialisation (RDF 1.1 N-Triples).
 *
 * N-Triples is a syntactic subset of Turtle, so parsing delegates to the
 * Turtle parser (every conforming N-Triples document parses identically).
 * Serialisation emits canonical one-triple-per-line output with absolute IRIs
 * — useful for diffing and as a graph exchange format.
 */
public object NTriples {
    /** Parse an N-Triples document. */
    public fun parse(input: String): Graph = Turtle.parse(input)

    /** Serialise a graph as N-Triples, sorted for deterministic output. */
    public fun serialize(graph: Graph): String =
        graph
            .map { "${render(it.subject)} ${render(it.predicate)} ${render(it.`object`)} .\n" }
            .sorted()
            .joinToString("")

    private fun render(term: Term): String = when (term) {
        is Term.IRI -> "<${TurtleSerializer.escapeIRI(term.value)}>"
        is Term.BlankNode -> {
            val id = term.id
            val safe = if (id.all { isLabelChar(it) } && id.firstOrNull() != '.') {
                id
            } else {
                "g" + id.map { if (isLabelChar(it)) it.toString() else "_" }.joinToString("")
            }
            "_:$safe"
        }
        is Term.LiteralTerm -> {
            val literal = term.literal
            val quoted = "\"${TurtleSerializer.escapeString(literal.lexicalForm)}\""
            when {
                literal.language != null -> "$quoted@${literal.language}"
                literal.datatypeIRI == XSD.STRING -> quoted
                else -> "$quoted^^<${TurtleSerializer.escapeIRI(literal.datatypeIRI)}>"
            }
        }
    }

    private fun isLabelChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
}
