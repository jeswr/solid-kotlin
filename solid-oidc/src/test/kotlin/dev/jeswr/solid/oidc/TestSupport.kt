package dev.jeswr.solid.oidc

import java.net.URI
import java.util.Base64

/** A compact JWT with the given payload and a fake signature. */
internal fun makeUnsignedJWT(payload: Map<String, Any?>): String {
    val enc = Base64.getUrlEncoder().withoutPadding()
    val header = enc.encodeToString(Json.write(mapOf("alg" to "ES256", "typ" to "JWT")).toByteArray())
    val body = enc.encodeToString(Json.write(payload).toByteArray())
    return "$header.$body.c2ln"
}

/** An [AuthorizationUserAgent] that immediately redirects with the configured code. */
internal class StubUserAgent(private val code: String = "test-code") : AuthorizationUserAgent {
    override fun authorize(authorizationURL: URI, redirectURI: URI): URI {
        val state = (authorizationURL.rawQuery ?: "").split("&")
            .first { it.startsWith("state=") }.substringAfter("=")
        return URI("$redirectURI?code=$code&state=$state")
    }
}
