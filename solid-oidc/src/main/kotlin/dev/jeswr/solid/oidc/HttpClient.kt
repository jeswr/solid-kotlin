package dev.jeswr.solid.oidc

/**
 * A value-type HTTP request, independent of any concrete client so the entire
 * stack can run against a mock transport in tests.
 */
public data class HttpRequest(
    public val method: String = "GET",
    public val url: String,
    public val headers: Map<String, String> = emptyMap(),
    public val body: ByteArray? = null,
) {
    /** Return a copy with one header set (case-preserving). */
    public fun withHeader(name: String, value: String): HttpRequest =
        copy(headers = headers + (name to value))

    override fun equals(other: Any?): Boolean =
        other is HttpRequest &&
            other.method == method &&
            other.url == url &&
            other.headers == headers &&
            other.body.contentEquals(body)

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        return result
    }
}

/** A value-type HTTP response with case-insensitive header access. */
public class HttpResponse(
    public val statusCode: Int,
    public val url: String,
    headers: Map<String, String>,
    public val body: ByteArray,
) {
    private val lowercasedHeaders: Map<String, String> =
        headers.entries.associate { it.key.lowercase() to it.value }

    /** The header value for [name] (case-insensitive), or null. */
    public fun header(name: String): String? = lowercasedHeaders[name.lowercase()]

    /** True for 2xx status codes. */
    public val isSuccess: Boolean get() = statusCode in 200..299

    /** The body decoded as UTF-8, for diagnostics. */
    public val bodyText: String get() = body.toString(Charsets.UTF_8)
}

/**
 * The transport seam: everything in solid-oidc and solid-pod performs HTTP
 * through this interface. Production code uses [OkHttpClientAdapter] (in
 * solid-reactive-auth) or your own; tests substitute a mock.
 */
public fun interface HttpClient {
    public fun send(request: HttpRequest): HttpResponse
}
