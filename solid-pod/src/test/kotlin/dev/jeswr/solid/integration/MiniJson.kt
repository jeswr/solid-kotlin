package dev.jeswr.solid.integration

/** A tiny JSON reader for parsing CSS account-API responses in integration tests. */
internal object MiniJson {
    fun parse(text: String): Any? = Parser(text).parseValue()

    private class Parser(val text: String) {
        var pos = 0

        fun parseValue(): Any? {
            skipWS()
            return when (peek()) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't', 'f' -> bool()
                'n' -> nul()
                else -> num()
            }
        }

        fun obj(): Map<String, Any?> {
            expect('{')
            val m = LinkedHashMap<String, Any?>()
            skipWS()
            if (peek() == '}') { pos++; return m }
            while (true) {
                skipWS(); val k = str(); skipWS(); expect(':'); m[k] = parseValue(); skipWS()
                when (next()) { ',' -> continue; '}' -> break; else -> error("bad obj") }
            }
            return m
        }

        fun arr(): List<Any?> {
            expect('[')
            val l = ArrayList<Any?>()
            skipWS()
            if (peek() == ']') { pos++; return l }
            while (true) {
                l.add(parseValue()); skipWS()
                when (next()) { ',' -> continue; ']' -> break; else -> error("bad arr") }
            }
            return l
        }

        fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = next()) {
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'u' -> { sb.append(text.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun bool(): Boolean =
            if (text.startsWith("true", pos)) { pos += 4; true } else { pos += 5; false }

        fun nul(): Any? { pos += 4; return null }

        fun num(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            val s = text.substring(start, pos)
            return if ('.' in s || 'e' in s || 'E' in s) s.toDouble() else s.toLong()
        }

        fun skipWS() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
        fun peek(): Char = text[pos]
        fun next(): Char = text[pos++]
        fun expect(c: Char) { if (next() != c) error("expected $c") }
    }
}
