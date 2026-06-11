package dev.jeswr.solid.oidc

import java.util.Base64

/** RFC 4648 §5 base64url helpers (the JOSE encoding), padding stripped. */
internal object Base64URL {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun decode(string: String): ByteArray = decoder.decode(string)
}

internal fun ByteArray.base64URL(): String = Base64URL.encode(this)
