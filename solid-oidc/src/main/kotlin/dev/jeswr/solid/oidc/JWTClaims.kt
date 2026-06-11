package dev.jeswr.solid.oidc

/**
 * Minimal JWT payload inspection — enough to read the `webid` and `exp` claims
 * of tokens this client received over TLS from the issuer it is talking to.
 * This performs **no signature verification**: clients act on these claims for
 * their own bookkeeping only; resource servers do the real verification.
 */
internal class JWTClaims private constructor(
    val webid: String?,
    val sub: String?,
    val iss: String?,
    val exp: Double?,
) {
    companion object {
        /** Decode the payload segment of a compact JWT, or null when invalid. */
        fun decode(jwt: String): JWTClaims? {
            val segments = jwt.split(".")
            if (segments.size != 3) return null
            return runCatching {
                val payload = Base64URL.decode(segments[1]).toString(Charsets.UTF_8)
                val map = Json.parseObject(payload)
                JWTClaims(
                    webid = map["webid"] as? String,
                    sub = map["sub"] as? String,
                    iss = map["iss"] as? String,
                    exp = (map["exp"] as? Number)?.toDouble(),
                )
            }.getOrNull()
        }
    }
}
