package dev.jeswr.solid.rdf

/**
 * RFC 3986 §5 reference resolution, implemented directly (java.net.URI
 * mishandles empty references and some dot-segment cases that Turtle relative
 * IRIs rely on).
 */
internal object IRIResolver {

    /**
     * Resolve [reference] against [base]. When [base] is null/empty or the
     * reference is absolute (has a scheme), the reference is returned as-is.
     */
    fun resolve(reference: String, base: String?): String {
        if (base.isNullOrEmpty()) return reference
        val r = Components(reference)
        if (r.scheme != null) return recompose(r.copy(path = removeDotSegments(r.path)))
        val b = Components(base)

        val target = Components(scheme = b.scheme)
        if (r.authority != null) {
            target.authority = r.authority
            target.path = removeDotSegments(r.path)
            target.query = r.query
        } else {
            target.authority = b.authority
            if (r.path.isEmpty()) {
                target.path = b.path
                target.query = r.query ?: b.query
            } else {
                target.path = if (r.path.startsWith("/")) {
                    removeDotSegments(r.path)
                } else {
                    removeDotSegments(merge(b, r.path))
                }
                target.query = r.query
            }
        }
        target.fragment = r.fragment
        return recompose(target)
    }

    private data class Components(
        var scheme: String? = null,
        var authority: String? = null,
        var path: String = "",
        var query: String? = null,
        var fragment: String? = null,
    ) {
        /** RFC 3986 appendix B parsing. */
        constructor(iri: String) : this() {
            var rest = iri
            val hash = rest.indexOf('#')
            if (hash >= 0) {
                fragment = rest.substring(hash + 1)
                rest = rest.substring(0, hash)
            }
            val colon = rest.indexOf(':')
            if (colon > 0 &&
                rest.substring(0, colon).all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' } &&
                rest.first().isLetter()
            ) {
                scheme = rest.substring(0, colon)
                rest = rest.substring(colon + 1)
            }
            val question = rest.indexOf('?')
            if (question >= 0) {
                query = rest.substring(question + 1)
                rest = rest.substring(0, question)
            }
            if (rest.startsWith("//")) {
                val afterSlashes = 2
                val pathStart = rest.indexOf('/', afterSlashes).let { if (it < 0) rest.length else it }
                authority = rest.substring(afterSlashes, pathStart)
                rest = rest.substring(pathStart)
            }
            path = rest
        }
    }

    /** RFC 3986 §5.3. */
    private fun recompose(c: Components): String = buildString {
        c.scheme?.let { append(it).append(':') }
        c.authority?.let { append("//").append(it) }
        append(c.path)
        c.query?.let { append('?').append(it) }
        c.fragment?.let { append('#').append(it) }
    }

    /** RFC 3986 §5.3 merge. */
    private fun merge(base: Components, reference: String): String {
        if (base.authority != null && base.path.isEmpty()) return "/$reference"
        val lastSlash = base.path.lastIndexOf('/')
        if (lastSlash < 0) return reference
        return base.path.substring(0, lastSlash + 1) + reference
    }

    /** RFC 3986 §5.2.4 remove_dot_segments. */
    private fun removeDotSegments(path: String): String {
        var input = path
        // Each entry is one path segment as it was consumed from the input
        // (typically a leading-slash segment), mirroring the reference array.
        val output = ArrayList<String>()
        while (input.isNotEmpty()) {
            when {
                input.startsWith("../") -> input = input.substring(3)
                input.startsWith("./") -> input = input.substring(2)
                input.startsWith("/./") -> input = input.substring(2)
                input == "/." -> input = "/"
                input.startsWith("/../") -> {
                    input = input.substring(3)
                    if (output.isNotEmpty()) output.removeAt(output.size - 1)
                }
                input == "/.." -> {
                    input = "/"
                    if (output.isNotEmpty()) output.removeAt(output.size - 1)
                }
                input == "." || input == ".." -> input = ""
                else -> {
                    val start = if (input.startsWith("/")) 1 else 0
                    val nextSlash = input.indexOf('/', start).let { if (it < 0) input.length else it }
                    output.add(input.substring(0, nextSlash))
                    input = input.substring(nextSlash)
                }
            }
        }
        return output.joinToString("")
    }
}
