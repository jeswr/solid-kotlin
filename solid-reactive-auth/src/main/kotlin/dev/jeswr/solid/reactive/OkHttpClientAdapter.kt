package dev.jeswr.solid.reactive

import dev.jeswr.solid.oidc.HttpClient
import dev.jeswr.solid.oidc.HttpRequest
import dev.jeswr.solid.oidc.HttpResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Bridges OkHttp to the solid-oidc/solid-pod [HttpClient] seam, so the whole
 * Solid stack can run over a real OkHttp transport. Use a **plain** OkHttp
 * client here (no reactive auth interceptor) for the bootstrap calls the auth
 * flow itself makes (discovery, token exchange); the reactive interceptor is
 * installed on the *application's* client for pod IO.
 */
public class OkHttpClientAdapter(private val client: OkHttpClient = OkHttpClient()) : HttpClient {
    override fun send(request: HttpRequest): HttpResponse {
        val builder = Request.Builder().url(request.url)
        val body = request.body?.toRequestBody()
        builder.method(request.method, body ?: emptyBodyFor(request.method))
        for ((name, value) in request.headers) builder.header(name, value)
        client.newCall(builder.build()).execute().use { response ->
            // Fold repeated field lines into one comma-joined value (RFC 7230
            // §3.2.2) instead of letting the last win — Solid servers send
            // several `Link:` lines (acl, type, describedby) and the discovery
            // code needs all of them, not just whichever arrives last.
            val headers = LinkedHashMap<String, String>()
            for (i in 0 until response.headers.size) {
                val name = response.headers.name(i)
                val value = response.headers.value(i)
                headers[name] = headers[name]?.let { "$it, $value" } ?: value
            }
            return HttpResponse(
                statusCode = response.code,
                url = response.request.url.toString(),
                headers = headers,
                body = response.body?.bytes() ?: ByteArray(0),
            )
        }
    }

    private fun emptyBodyFor(method: String): okhttp3.RequestBody? =
        if (method in setOf("POST", "PUT", "PATCH", "DELETE")) ByteArray(0).toRequestBody() else null
}
