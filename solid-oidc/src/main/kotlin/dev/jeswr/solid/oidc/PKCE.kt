package dev.jeswr.solid.oidc

import java.security.MessageDigest
import java.security.SecureRandom

/** PKCE (RFC 7636), S256 method. */
public class PKCE private constructor(
    public val verifier: String,
    public val challenge: String,
) {
    public companion object {
        private val random = SecureRandom()

        /** A fresh high-entropy verifier and its S256 challenge. */
        public operator fun invoke(): PKCE {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return fromVerifier(bytes.base64URL())
        }

        /** Build a PKCE pair from a known verifier (for test vectors). */
        public fun fromVerifier(verifier: String): PKCE {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return PKCE(verifier, digest.base64URL())
        }
    }
}
