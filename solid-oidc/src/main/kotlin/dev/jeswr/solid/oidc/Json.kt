package dev.jeswr.solid.oidc

/**
 * A minimal JSON reader/writer — enough for OIDC discovery docs, token
 * responses, registration metadata, and DPoP/JWT payloads. Keeps the module
 * dependency-free (mirroring the Swift SDK's zero-third-party stance); RDF goes
 * through solid-rdf, JSON through this.
 *
 * Values are `String`, `Double`, `Long`, `Boolean`, `null`, `List<Any?>`, or
 * `Map<String, Any?>`.
 */
internal object Json {
    fun parse(text: String): Any? = Parser(text).parseValue()

    fun parseObject(text: String): Map<String, Any?> =
        @Suppress("UNCHECKED_CAST")
        (parse(text) as? Map<String, Any?>) ?: emptyMap()

    fun write(value: Any?): String = buildString { writeValue(value, this) }

    private fun writeValue(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(value, sb)
            is Boolean -> sb.append(value.toString())
            is Int -> sb.append(value.toString())
            is Long -> sb.append(value.toString())
            is Double -> sb.append(if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(k.toString(), sb)
                    sb.append(':')
                    writeValue(v, sb)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    writeValue(v, sb)
                }
                sb.append(']')
            }
            else -> writeString(value.toString(), sb)
        }
    }

    /** Write a JSON object with keys emitted in sorted order (canonical). */
    fun writeSorted(value: Map<String, Any?>): String = buildString {
        append('{')
        val keys = value.keys.sorted()
        keys.forEachIndexed { i, k ->
            if (i > 0) append(',')
            writeString(k, this)
            append(':')
            writeValue(value[k], this)
        }
        append('}')
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWS()
            return when (val c = peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> if (c == '-' || c in '0'..'9') parseNumber() else error("unexpected '$c'")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWS()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWS()
                val key = parseString()
                skipWS()
                expect(':')
                map[key] = parseValue()
                skipWS()
                when (next()) {
                    ',' -> continue
                    '}' -> break
                    else -> error("expected , or }")
                }
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWS()
            if (peek() == ']') { pos++; return list }
            while (true) {
                list.add(parseValue())
                skipWS()
                when (next()) {
                    ',' -> continue
                    ']' -> break
                    else -> error("expected , or ]")
                }
            }
            return list
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            val hex = text.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> error("bad escape \\$e")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseBoolean(): Boolean =
            if (text.startsWith("true", pos)) { pos += 4; true }
            else if (text.startsWith("false", pos)) { pos += 5; false }
            else error("bad boolean")

        private fun parseNull(): Any? {
            if (text.startsWith("null", pos)) { pos += 4; return null }
            error("bad null")
        }

        private fun parseNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && (text[pos] in '0'..'9' || text[pos] in ".eE+-".toCharArray())) pos++
            val s = text.substring(start, pos)
            return if (s.contains('.') || s.contains('e') || s.contains('E')) s.toDouble() else s.toLong()
        }

        private fun skipWS() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun peek(): Char = if (pos < text.length) text[pos] else error("unexpected end")
        private fun next(): Char = if (pos < text.length) text[pos++] else error("unexpected end")
        private fun expect(c: Char) {
            if (next() != c) error("expected '$c'")
        }

        private fun error(msg: String): Nothing = throw IllegalArgumentException("JSON parse error at $pos: $msg")
    }
}
