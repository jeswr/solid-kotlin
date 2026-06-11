package dev.jeswr.solid.oidc

import java.net.URI
import java.net.URLEncoder
import java.util.Base64

/** A successful token endpoint response. */
internal class TokenResponse(
    val accessToken: String,
    val tokenType: String,
    val idToken: String?,
    val refreshToken: String?,
    val expiresIn: Double?,
) {
    companion object {
        fun from(map: Map<String, Any?>): TokenResponse = TokenResponse(
            accessToken = map["access_token"] as? String ?: error("missing access_token"),
            tokenType = map["token_type"] as? String ?: "DPoP",
            idToken = map["id_token"] as? String,
            refreshToken = map["refresh_token"] as? String,
            expiresIn = (map["expires_in"] as? Number)?.toDouble(),
        )
    }
}

/**
 * Token endpoint requests with DPoP binding and `DPoP-Nonce` retry
 * (RFC 9449 §8).
 */
internal object TokenClient {
    fun request(
        tokenEndpoint: URI,
        parameters: Map<String, String>,
        clientCredentials: ClientCredentials?,
        dpopKey: DPoPKey,
        httpClient: HttpClient,
    ): TokenResponse {
        var attemptNonce: String? = null
        repeat(2) { attempt ->
            var headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Accept" to "application/json",
                "DPoP" to dpopKey.proof(method = "POST", url = tokenEndpoint.toString(), nonce = attemptNonce),
            )
            val params = LinkedHashMap(parameters)
            if (clientCredentials != null) {
                val secret = clientCredentials.clientSecret
                if (secret != null) {
                    val pair = "${formEncode(clientCredentials.clientID)}:${formEncode(secret)}"
                    val basic = Base64.getEncoder().encodeToString(pair.toByteArray())
                    headers = headers + ("Authorization" to "Basic $basic")
                } else {
                    params["client_id"] = clientCredentials.clientID
                }
            }
            val response = httpClient.send(
                HttpRequest(
                    method = "POST",
                    url = tokenEndpoint.toString(),
                    headers = headers,
                    body = encodeForm(params).toByteArray(),
                ),
            )
            if (response.isSuccess) {
                return runCatching { TokenResponse.from(Json.parseObject(response.bodyText)) }.getOrElse {
                    throw SolidOidcException.TokenRequestFailed(
                        response.statusCode,
                        null,
                        "malformed token response: $it",
                    )
                }
            }
            val oauthError = runCatching { Json.parseObject(response.bodyText) }.getOrNull()
            val errorCode = oauthError?.get("error") as? String
            if (attempt == 0 && errorCode == "use_dpop_nonce") {
                val nonce = response.header("DPoP-Nonce")
                if (nonce != null) {
                    attemptNonce = nonce
                    return@repeat
                }
            }
            throw SolidOidcException.TokenRequestFailed(
                response.statusCode,
                errorCode,
                (oauthError?.get("error_description") as? String) ?: response.bodyText,
            )
        }
        error("unreachable")
    }

    fun encodeForm(parameters: Map<String, String>): String =
        parameters.entries
            .sortedBy { it.key }
            .joinToString("&") { "${formEncode(it.key)}=${formEncode(it.value)}" }

    fun formEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8)
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
}
