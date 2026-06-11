package dev.jeswr.solid.rdf

/**
 * Turtle serialisation: deterministic output (sorted subjects/predicates/
 * objects), prefix shortening, `a` for `rdf:type`, grouped predicate-object
 * lists. Blank nodes are emitted with stable `_:b<n>` labels (no inlining —
 * correct and simple; inlining is a cosmetic optimisation).
 */
internal object TurtleSerializer {
    fun serialize(graph: Graph, prefixes: Map<String, String>, base: String?): String {
        val out = StringBuilder()
        val sortedPrefixes = prefixes.entries.sortedBy { it.key }
        for ((name, namespace) in sortedPrefixes) {
            out.append("@prefix $name: <${escapeIRI(namespace)}> .\n")
        }
        if (sortedPrefixes.isNotEmpty()) out.append('\n')

        val labels = HashMap<String, String>()
        fun bnodeLabel(id: String): String = labels.getOrPut(id) { "_:b${labels.size}" }

        fun renderIRI(iri: String): String {
            for ((name, namespace) in sortedPrefixes) {
                if (iri.startsWith(namespace)) {
                    val local = iri.substring(namespace.length)
                    if (isSafeLocalName(local)) return "$name:$local"
                }
            }
            return "<${escapeIRI(iri)}>"
        }

        fun renderLiteral(literal: Literal): String {
            val quoted = "\"${escapeString(literal.lexicalForm)}\""
            return when {
                literal.language != null -> "$quoted@${literal.language}"
                literal.datatypeIRI == XSD.STRING -> quoted
                else -> "$quoted^^${renderIRI(literal.datatypeIRI)}"
            }
        }

        fun renderTerm(term: Term): String = when (term) {
            is Term.IRI -> if (term.value == RDF.NIL) "()" else renderIRI(term.value)
            is Term.BlankNode -> bnodeLabel(term.id)
            is Term.LiteralTerm -> renderLiteral(literal = term.literal)
        }

        val bySubject = graph.groupBy { it.subject }
        val subjects = bySubject.keys.sortedWith(compareBy { sortKey(it) })
        for (subject in subjects) {
            val tripleList = bySubject[subject] ?: emptyList()
            out.append(renderTerm(subject))
            val byPredicate = tripleList.groupBy { it.predicate }
            val predicates = byPredicate.keys.sortedWith(compareBy { predicateSortKey(it) })
            var first = true
            for (predicate in predicates) {
                out.append(if (first) " " else ";\n    ")
                first = false
                val verb = if (predicate == Term.IRI(RDF.TYPE)) "a" else renderTerm(predicate)
                val objects = (byPredicate[predicate] ?: emptyList())
                    .map { renderTerm(it.`object`) }
                    .sorted()
                out.append("$verb ${objects.joinToString(", ")}")
            }
            out.append(" .\n")
        }
        return out.toString()
    }

    private fun predicateSortKey(term: Term): String =
        if (term == Term.IRI(RDF.TYPE)) "" else sortKey(term)

    private fun sortKey(term: Term): String = when (term) {
        is Term.IRI -> "0${term.value}"
        is Term.BlankNode -> "1${term.id}"
        is Term.LiteralTerm -> "2${term.literal.lexicalForm}"
    }

    private fun isSafeLocalName(local: String): Boolean {
        if (local.isEmpty() || local.first() == '-' || local.first() == '.' || local.last() == '.') {
            return false
        }
        return local.all { c ->
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c == '.'
        }
    }

    internal fun escapeIRI(iri: String): String {
        val out = StringBuilder()
        for (c in iri.codePoints()) {
            if (c == '<'.code || c == '>'.code || c == '"'.code || c == '{'.code ||
                c == '}'.code || c == '|'.code || c == '^'.code || c == '`'.code ||
                c == '\\'.code || c <= 0x20
            ) {
                out.append("\\u%04X".format(c))
            } else {
                out.appendCodePoint(c)
            }
        }
        return out.toString()
    }

    internal fun escapeString(value: String): String {
        val out = StringBuilder()
        for (ch in value) {
            when (ch.code) {
                '\\'.code -> out.append("\\\\")
                '"'.code -> out.append("\\\"")
                '\n'.code -> out.append("\\n")
                '\r'.code -> out.append("\\r")
                '\t'.code -> out.append("\\t")
                0x08 -> out.append("\\b")
                0x0C -> out.append("\\f")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}
