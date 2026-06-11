package dev.jeswr.solid.testsupport

import dev.jeswr.solid.oidc.HttpClient
import dev.jeswr.solid.oidc.HttpRequest
import dev.jeswr.solid.oidc.HttpResponse

/**
 * A scriptable [HttpClient]: register handlers per method + URL prefix (last
 * registration wins); records every request for assertions.
 */
public class MockHttpClient : HttpClient {
    private data class Route(
        val method: String,
        val urlPrefix: String,
        val handler: (HttpRequest) -> HttpResponse,
    )

    private val routes = mutableListOf<Route>()
    private val requests = mutableListOf<HttpRequest>()
    private val lock = Any()

    public fun on(
        method: String,
        urlPrefix: String,
        handler: (HttpRequest) -> HttpResponse,
    ): MockHttpClient {
        synchronized(lock) { routes.add(Route(method, urlPrefix, handler)) }
        return this
    }

    override fun send(request: HttpRequest): HttpResponse {
        val route = synchronized(lock) {
            requests.add(request)
            routes.lastOrNull { it.method == request.method && request.url.startsWith(it.urlPrefix) }
        }
        return route?.handler?.invoke(request)
            ?: HttpResponse(
                statusCode = 404,
                url = request.url,
                headers = emptyMap(),
                body = "no mock route for ${request.method} ${request.url}".toByteArray(),
            )
    }

    public fun recordedRequests(): List<HttpRequest> = synchronized(lock) { requests.toList() }
}

/** Build a JSON [HttpResponse] from a value (Map/List/scalars). */
public fun jsonResponse(`object`: Any?, url: String, status: Int = 200): HttpResponse =
    HttpResponse(
        statusCode = status,
        url = url,
        headers = mapOf("Content-Type" to "application/json"),
        body = SimpleJson.write(`object`).toByteArray(),
    )

/** Build a `text/turtle` [HttpResponse]. */
public fun turtleResponse(
    turtle: String,
    url: String,
    etag: String? = null,
    extraHeaders: Map<String, String> = emptyMap(),
): HttpResponse {
    val headers = HashMap(extraHeaders)
    headers["Content-Type"] = "text/turtle"
    if (etag != null) headers["ETag"] = etag
    return HttpResponse(statusCode = 200, url = url, headers = headers, body = turtle.toByteArray())
}
