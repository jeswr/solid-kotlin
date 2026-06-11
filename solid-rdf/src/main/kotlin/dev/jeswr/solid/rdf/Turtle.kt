package dev.jeswr.solid.rdf

/**
 * A Turtle (or N-Triples — Turtle is a superset) parse failure, with the
 * 1-based line/column of the offending character.
 */
public class TurtleParseException(
    public val reason: String,
    public val line: Int,
    public val column: Int,
) : Exception("Turtle parse error at $line:$column: $reason")

/**
 * Turtle parsing and serialisation.
 *
 * Covers the subset of Turtle 1.1 that Solid resources (WebID profiles,
 * containers, ACLs) use in practice — which is nearly all of it:
 * `@prefix`/`@base` (and SPARQL-style `PREFIX`/`BASE`), IRIs and prefixed
 * names, literals with language tags and datatypes (short and long quoted
 * forms, numeric and boolean shorthand), blank node labels, anonymous blank
 * nodes and property lists, and RDF collections.
 */
public object Turtle {
    /**
     * Parse a Turtle document into a [Graph].
     *
     * @param input The Turtle source text.
     * @param base The base IRI for resolving relative IRIs — pass the URL the
     *   document was retrieved from. An in-document `@base` overrides it.
     */
    public fun parse(input: String, base: String? = null): Graph =
        TurtleDocParser(input, base).parseDocument()

    /** Serialise a graph to Turtle. See [TurtleSerializer]. */
    public fun serialize(
        graph: Graph,
        prefixes: Map<String, String> = emptyMap(),
        base: String? = null,
    ): String = TurtleSerializer.serialize(graph, prefixes, base)
}

/** Recursive-descent Turtle parser over a code-point buffer. */
private class TurtleDocParser(input: String, base: String?) {
    private val scalars: IntArray = input.codePoints().toArray()
    private var pos = 0
    private var base: String? = base
    private val prefixes = HashMap<String, String>()
    private val triples = ArrayList<Triple>()
    private var anonCounter = 0

    fun parseDocument(): Graph {
        skipWS()
        while (pos < scalars.size) {
            parseStatement()
            skipWS()
        }
        return Graph(triples)
    }

    private fun parseStatement() {
        if (peek() == '@'.code) {
            parseAtDirective()
            return
        }
        if (matchKeyword("PREFIX")) {
            parsePrefixBody()
            return
        }
        if (matchKeyword("BASE")) {
            parseBaseBody()
            return
        }
        parseTriples()
        expect('.'.code)
    }

    private fun parseAtDirective() {
        pos += 1 // '@'
        when {
            matchKeyword("prefix") -> { parsePrefixBody(); expect('.'.code) }
            matchKeyword("base") -> { parseBaseBody(); expect('.'.code) }
            else -> throw error("unknown directive")
        }
    }

    private fun parsePrefixBody() {
        skipWS()
        val name = parsePNameNS()
        skipWS()
        prefixes[name] = parseIRIRef()
    }

    private fun parseBaseBody() {
        skipWS()
        base = parseIRIRef()
    }

    // Triples

    private fun parseTriples() {
        skipWS()
        val subject: Term
        when (peek()) {
            '['.code -> {
                val (node, wasPropertyList) = parseBlankNodeBracket()
                subject = node
                skipWS()
                if (peek() == '.'.code && wasPropertyList) return
            }
            '('.code -> subject = parseCollection()
            else -> subject = parseSubjectTerm()
        }
        parsePredicateObjectList(subject)
    }

    private fun parsePredicateObjectList(subject: Term) {
        while (true) {
            skipWS()
            val predicate = parseVerb()
            parseObjectList(subject, predicate)
            skipWS()
            if (peek() != ';'.code) return
            while (peek() == ';'.code) {
                pos += 1
                skipWS()
            }
            if (peek() == '.'.code || peek() == ']'.code || pos >= scalars.size) return
        }
    }

    private fun parseObjectList(subject: Term, predicate: Term) {
        while (true) {
            skipWS()
            val obj = parseObject()
            triples.add(Triple(subject, predicate, obj))
            skipWS()
            if (peek() != ','.code) return
            pos += 1
        }
    }

    private fun parseVerb(): Term {
        if (peek() == 'a'.code && !isPNChar(peekAt(1)) && peekAt(1) != ':'.code) {
            pos += 1
            return Term.IRI(RDF.TYPE)
        }
        return parseIRITerm()
    }

    private fun parseSubjectTerm(): Term {
        if (peek() == '_'.code) return parseBlankNodeLabel()
        return parseIRITerm()
    }

    private fun parseObject(): Term {
        val c = peek() ?: throw error("unexpected end of input, expected object")
        return when (c) {
            '<'.code -> parseIRITerm()
            '_'.code -> parseBlankNodeLabel()
            '['.code -> parseBlankNodeBracket().first
            '('.code -> parseCollection()
            '"'.code, '\''.code -> parseRDFLiteral()
            '+'.code, '-'.code, '.'.code, in '0'.code..'9'.code -> parseNumericLiteral()
            else -> {
                if (matchKeyword("true")) return Term.LiteralTerm(Literal.boolean(true))
                if (matchKeyword("false")) return Term.LiteralTerm(Literal.boolean(false))
                parseIRITerm()
            }
        }
    }

    // Terms

    private fun parseIRITerm(): Term {
        if (peek() == '<'.code) {
            return Term.IRI(IRIResolver.resolve(parseIRIRef(), base))
        }
        return Term.IRI(parsePrefixedName())
    }

    private fun parseIRIRef(): String {
        expect('<'.code)
        val sb = StringBuilder()
        while (true) {
            val c = peek() ?: throw error("unterminated IRI")
            pos += 1
            when {
                c == '>'.code -> return sb.toString()
                c == '\\'.code -> sb.appendCodePoint(parseUCharEscape())
                c == ' '.code || c == '<'.code || c == '"'.code || c == '{'.code ||
                    c == '}'.code || c == '|'.code || c == '^'.code || c == '`'.code || c <= 0x20 ->
                    throw error("illegal character in IRI")
                else -> sb.appendCodePoint(c)
            }
        }
    }

    private fun parseUCharEscape(): Int {
        val kind = peek()
        if (kind != 'u'.code && kind != 'U'.code) throw error("only \\u/\\U escapes are allowed in IRIs")
        pos += 1
        return parseHexEscape(if (kind == 'u'.code) 4 else 8)
    }

    private fun parseHexEscape(length: Int): Int {
        var value = 0
        repeat(length) {
            val c = peek()
            val digit = if (c != null) Character.digit(c, 16) else -1
            if (digit < 0) throw error("invalid \\u escape")
            value = value * 16 + digit
            pos += 1
        }
        if (value > 0x10FFFF || (value in 0xD800..0xDFFF)) throw error("invalid code point in escape")
        return value
    }

    /** PNAME_NS: the (possibly empty) prefix label before ':'. */
    private fun parsePNameNS(): String {
        val sb = StringBuilder()
        while (true) {
            val c = peek() ?: break
            if (c == ':'.code) break
            if (isPNChar(c) || c == '.'.code) {
                sb.appendCodePoint(c)
                pos += 1
            } else {
                break
            }
        }
        expect(':'.code)
        if (sb.endsWith(".")) throw error("prefix label cannot end with '.'")
        return sb.toString()
    }

    private fun parsePrefixedName(): String {
        val name = parsePNameNS()
        val namespace = prefixes[name] ?: throw error("undefined prefix '$name:'")
        val local = StringBuilder()
        while (true) {
            val c = peek() ?: break
            when {
                c == '%'.code -> {
                    local.appendCodePoint(c)
                    pos += 1
                    repeat(2) {
                        val h = peek()
                        if (h == null || Character.digit(h, 16) < 0) {
                            throw error("invalid %-encoding in prefixed name")
                        }
                        local.appendCodePoint(h)
                        pos += 1
                    }
                }
                c == '\\'.code -> {
                    pos += 1
                    val escaped = peek()
                    if (escaped == null || escaped !in PN_LOCAL_ESCAPES) {
                        throw error("invalid local-name escape")
                    }
                    local.appendCodePoint(escaped)
                    pos += 1
                }
                isPNChar(c) || c == ':'.code || c == '.'.code -> {
                    local.appendCodePoint(c)
                    pos += 1
                }
                else -> break
            }
        }
        while (local.endsWith(".")) {
            local.deleteCharAt(local.length - 1)
            pos -= 1
        }
        return namespace + local.toString()
    }

    private fun parseBlankNodeLabel(): Term {
        expect('_'.code)
        expect(':'.code)
        val sb = StringBuilder()
        while (true) {
            val c = peek() ?: break
            if (isPNChar(c) || c == '.'.code) {
                sb.appendCodePoint(c)
                pos += 1
            } else {
                break
            }
        }
        while (sb.endsWith(".")) {
            sb.deleteCharAt(sb.length - 1)
            pos -= 1
        }
        if (sb.isEmpty()) throw error("empty blank node label")
        return Term.BlankNode(sb.toString())
    }

    /** `[` … `]`: ANON or a blank node property list. */
    private fun parseBlankNodeBracket(): Pair<Term, Boolean> {
        expect('['.code)
        skipWS()
        val node = freshBlankNode()
        if (peek() == ']'.code) {
            pos += 1
            return node to false
        }
        parsePredicateObjectList(node)
        skipWS()
        expect(']'.code)
        return node to true
    }

    private fun parseCollection(): Term {
        expect('('.code)
        val items = ArrayList<Term>()
        while (true) {
            skipWS()
            if (peek() == ')'.code) {
                pos += 1
                break
            }
            if (pos >= scalars.size) throw error("unterminated collection")
            items.add(parseObject())
        }
        var tail: Term = Term.IRI(RDF.NIL)
        for (item in items.asReversed()) {
            val node = freshBlankNode()
            triples.add(Triple(node, Term.IRI(RDF.FIRST), item))
            triples.add(Triple(node, Term.IRI(RDF.REST), tail))
            tail = node
        }
        return tail
    }

    private fun freshBlankNode(): Term {
        anonCounter += 1
        return Term.BlankNode(".anon$anonCounter")
    }

    // Literals

    private fun parseRDFLiteral(): Term {
        val lexical = parseStringBody()
        if (peek() == '@'.code) {
            pos += 1
            val tag = StringBuilder()
            while (true) {
                val c = peek() ?: break
                if (c == '-'.code || (c < 128 && Character.isLetterOrDigit(c))) {
                    tag.appendCodePoint(c)
                    pos += 1
                } else {
                    break
                }
            }
            val t = tag.toString()
            if (t.isEmpty() || t.first() == '-' || t.last() == '-') throw error("invalid language tag")
            return Term.LiteralTerm(Literal.lang(lexical, t))
        }
        if (peek() == '^'.code) {
            pos += 1
            expect('^'.code)
            skipWS()
            val datatype = parseIRITerm().iriValue ?: throw error("datatype must be an IRI")
            return Term.LiteralTerm(Literal(lexical, datatype))
        }
        return Term.LiteralTerm(Literal(lexical))
    }

    private fun parseStringBody(): String {
        val quote = peek()
        if (quote != '"'.code && quote != '\''.code) throw error("expected string literal")
        pos += 1
        var long = false
        if (peek() == quote && peekAt(1) == quote) {
            pos += 2
            long = true
        } else if (peek() == quote) {
            pos += 1
            return ""
        }
        val sb = StringBuilder()
        while (true) {
            val c = peek() ?: throw error("unterminated string literal")
            when {
                c == quote -> {
                    if (!long) {
                        pos += 1
                        return sb.toString()
                    }
                    if (peekAt(1) == quote && peekAt(2) == quote) {
                        pos += 3
                        return sb.toString()
                    }
                    sb.appendCodePoint(c)
                    pos += 1
                }
                c == '\\'.code -> {
                    pos += 1
                    sb.appendCodePoint(parseStringEscape())
                }
                !long && (c == '\n'.code || c == '\r'.code) -> throw error("newline in single-line string")
                else -> {
                    sb.appendCodePoint(c)
                    pos += 1
                }
            }
        }
    }

    private fun parseStringEscape(): Int {
        val c = peek() ?: throw error("dangling escape")
        pos += 1
        return when (c) {
            't'.code -> '\t'.code
            'b'.code -> 0x8
            'n'.code -> '\n'.code
            'r'.code -> '\r'.code
            'f'.code -> 0xC
            '"'.code -> '"'.code
            '\''.code -> '\''.code
            '\\'.code -> '\\'.code
            'u'.code -> parseHexEscape(4)
            'U'.code -> parseHexEscape(8)
            else -> throw error("invalid string escape")
        }
    }

    private fun parseNumericLiteral(): Term {
        val sb = StringBuilder()
        if (peek() == '+'.code || peek() == '-'.code) {
            sb.appendCodePoint(peek()!!)
            pos += 1
        }
        var sawDigit = false
        var sawDot = false
        var sawExponent = false
        loop@ while (true) {
            val c = peek() ?: break
            when (c) {
                in '0'.code..'9'.code -> {
                    sawDigit = true
                    sb.appendCodePoint(c)
                    pos += 1
                }
                '.'.code -> {
                    if (sawDot || sawExponent) break@loop
                    val next = peekAt(1)
                    if (next == null || next !in '0'.code..'9'.code) break@loop
                    sawDot = true
                    sb.appendCodePoint(c)
                    pos += 1
                }
                'e'.code, 'E'.code -> {
                    if (sawExponent || !sawDigit) break@loop
                    sawExponent = true
                    sb.appendCodePoint(c)
                    pos += 1
                    if (peek() == '+'.code || peek() == '-'.code) {
                        sb.appendCodePoint(peek()!!)
                        pos += 1
                    }
                }
                else -> break@loop
            }
        }
        if (!sawDigit) throw error("expected numeric literal")
        val datatype = if (sawExponent) XSD.DOUBLE else if (sawDot) XSD.DECIMAL else XSD.INTEGER
        return Term.LiteralTerm(Literal(sb.toString(), datatype))
    }

    // Low-level scanning

    private fun peek(): Int? = if (pos < scalars.size) scalars[pos] else null

    private fun peekAt(offset: Int): Int? =
        if (pos + offset < scalars.size) scalars[pos + offset] else null

    private fun skipWS() {
        while (true) {
            val c = peek() ?: return
            when {
                c == '#'.code -> {
                    while (true) {
                        val n = peek() ?: break
                        if (n == '\n'.code || n == '\r'.code) break
                        pos += 1
                    }
                }
                c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code -> pos += 1
                else -> return
            }
        }
    }

    private fun expect(expected: Int) {
        skipWS()
        if (peek() != expected) throw error("expected '${String(Character.toChars(expected))}'")
        pos += 1
    }

    private fun matchKeyword(keyword: String): Boolean {
        val kw = keyword.codePoints().toArray()
        if (pos + kw.size > scalars.size) return false
        for (offset in kw.indices) {
            val actual = scalars[pos + offset]
            val expected = kw[offset]
            if (actual != expected &&
                Character.toLowerCase(actual) != Character.toLowerCase(expected)
            ) {
                return false
            }
        }
        val after = peekAt(kw.size)
        if (after != null && (isPNChar(after) || after == ':'.code)) return false
        pos += kw.size
        return true
    }

    private fun isPNChar(c: Int?): Boolean {
        if (c == null) return false
        if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code) return true
        if (c == '_'.code || c == '-'.code) return true
        return c >= 0x00C0 && c != 0x00D7 && c != 0x00F7 && c < 0xFDD0
    }

    private fun error(message: String): TurtleParseException {
        var line = 1
        var column = 1
        for (index in 0 until minOf(pos, scalars.size)) {
            if (scalars[index] == '\n'.code) {
                line += 1
                column = 1
            } else {
                column += 1
            }
        }
        return TurtleParseException(message, line, column)
    }

    companion object {
        private val PN_LOCAL_ESCAPES: Set<Int> =
            "_~.-!\$&'()*+,;=/?#@%".codePoints().toArray().toSet()
    }
}
