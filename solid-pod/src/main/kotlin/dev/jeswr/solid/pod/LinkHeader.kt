package dev.jeswr.solid.pod

import java.net.URI

/**
 * Minimal RFC 8288 `Link` header parsing — enough to extract link targets by
 * `rel` (`rel="acl"`, `rel="type"`), handling quoted parameter values and
 * commas inside quoted strings.
 */
internal object LinkHeader {
    data class Entry(val target: String, val params: Map<String, String>)

    fun parse(value: String?): List<Entry> {
        if (value == null) return emptyList()
        val entries = ArrayList<Entry>()
        for (part in splitTopLevel(value)) {
            val open = part.indexOf('<')
            if (open < 0) continue
            val close = part.indexOf('>', open)
            if (close < 0) continue
            val target = part.substring(open + 1, close)
            val params = LinkedHashMap<String, String>()
            var rest = part.substring(close + 1)
            while (true) {
                val semicolon = rest.indexOf(';')
                if (semicolon < 0) break
                rest = rest.substring(semicolon + 1)
                val eq = rest.indexOf('=')
                if (eq < 0) break
                val key = rest.substring(0, eq).trim().lowercase()
                if (key.isEmpty()) break
                rest = rest.substring(eq + 1)
                val valuePart: String
                if (rest.trimStart().startsWith("\"")) {
                    val trimmed = rest.trimStart().substring(1)
                    val sb = StringBuilder()
                    var i = 0
                    while (i < trimmed.length && trimmed[i] != '"') {
                        if (trimmed[i] == '\\' && i + 1 < trimmed.length) i++
                        sb.append(trimmed[i])
                        i++
                    }
                    valuePart = sb.toString()
                    rest = if (i < trimmed.length) trimmed.substring(i + 1) else ""
                } else {
                    val end = rest.indexOf(';').let { if (it < 0) rest.length else it }
                    valuePart = rest.substring(0, end).trim()
                    rest = rest.substring(end)
                }
                if (key !in params) params[key] = valuePart
            }
            entries.add(Entry(target, params))
        }
        return entries
    }

    /**
     * Absolute targets of every link with the given [rel] (space-separated list
     * per RFC 8288), resolved against [base].
     */
    fun targets(value: String?, rel: String, base: URI): List<URI> {
        val wanted = rel.lowercase()
        return parse(value).mapNotNull { entry ->
            val rels = (entry.params["rel"] ?: "").lowercase().split(" ").filter { it.isNotEmpty() }
            if (wanted !in rels) return@mapNotNull null
            runCatching { base.resolve(entry.target) }.getOrNull()
        }
    }

    private fun splitTopLevel(value: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var previous: Char? = null
        for (c in value) {
            if (c == '"' && previous != '\\') inQuotes = !inQuotes
            if (c == ',' && !inQuotes) {
                if (current.toString().trim().isNotEmpty()) parts.add(current.toString())
                current.clear()
            } else {
                current.append(c)
            }
            previous = c
        }
        if (current.toString().trim().isNotEmpty()) parts.add(current.toString())
        return parts
    }
}
