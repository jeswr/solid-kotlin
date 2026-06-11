package dev.jeswr.solid.oidc

import java.math.BigInteger
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.UUID

/**
 * A DPoP proof key (RFC 9449): a P-256 signing key whose public half is
 * embedded as a JWK in every proof. Create one per session and keep it with the
 * tokens — the access token is bound to this key's thumbprint.
 *
 * Signatures use `java.security` ES256 (SHA256withECDSAinP1363Format) so the
 * 64-byte raw r‖s is produced directly in JOSE format. On Android the same key
 * material can be backed by the Android Keystore (see the README); the proof
 * shape is identical.
 */
public class DPoPKey private constructor(
    /**
     * The raw private scalar bytes (32 bytes, P-256). Persist alongside the
     * session so restored sessions can keep using their bound tokens.
     */
    public val rawRepresentation: ByteArray,
) {
    private val privateKey: ECPrivateKey
    private val publicKey: ECPublicKey

    init {
        require(rawRepresentation.size == 32) { "P-256 private scalar must be 32 bytes" }
        val kf = KeyFactory.getInstance("EC")
        val params = p256Params()
        val s = BigInteger(1, rawRepresentation)
        privateKey = kf.generatePrivate(ECPrivateKeySpec(s, params)) as ECPrivateKey
        // Derive the public point Q = s·G via a temporary keypair is not direct
        // in the JCA; instead re-derive from the scalar using BouncyCastle-free
        // math through the keypair generator seeded deterministically is also
        // awkward. We compute Q from the stored full keypair instead.
        publicKey = derivePublicKey(s, params)
    }

    /** The public key as JWK parameters (`kty`/`crv`/`x`/`y`). */
    public val publicJWK: Map<String, String>
        get() {
            val w: ECPoint = publicKey.w
            val x = unsigned32(w.affineX)
            val y = unsigned32(w.affineY)
            return mapOf(
                "kty" to "EC",
                "crv" to "P-256",
                "x" to x.base64URL(),
                "y" to y.base64URL(),
            )
        }

    /**
     * The JWK SHA-256 thumbprint (RFC 7638) — the value servers bind the access
     * token to via the `cnf.jkt` claim.
     */
    public val jwkThumbprint: String
        get() {
            val jwk = publicJWK
            // RFC 7638: required members only, lexicographic order, no whitespace.
            val canonical =
                """{"crv":"${jwk["crv"]}","kty":"${jwk["kty"]}","x":"${jwk["x"]}","y":"${jwk["y"]}"}"""
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).base64URL()
        }

    /**
     * Build a DPoP proof JWT (RFC 9449 §4) for one HTTP request.
     *
     * @param method The HTTP method (`htm`).
     * @param url The request URL; query and fragment are stripped for `htu`.
     * @param accessToken When presenting an access token to a resource server,
     *   pass it so the proof carries its hash (`ath`).
     * @param nonce A server-provided `DPoP-Nonce`, echoed back on retry.
     * @param issuedAtSeconds Override for testing (epoch seconds).
     */
    public fun proof(
        method: String,
        url: String,
        accessToken: String? = null,
        nonce: String? = null,
        issuedAtSeconds: Long = System.currentTimeMillis() / 1000,
    ): String {
        val header: Map<String, Any?> = mapOf(
            "alg" to "ES256",
            "typ" to "dpop+jwt",
            "jwk" to publicJWK,
        )
        val payload = LinkedHashMap<String, Any?>()
        payload["jti"] = UUID.randomUUID().toString()
        payload["htm"] = method.uppercase()
        payload["htu"] = htu(url)
        payload["iat"] = issuedAtSeconds
        if (accessToken != null) {
            payload["ath"] = MessageDigest.getInstance("SHA-256")
                .digest(accessToken.toByteArray()).base64URL()
        }
        if (nonce != null) payload["nonce"] = nonce

        val headerB64 = Json.writeSorted(header).toByteArray().base64URL()
        val payloadB64 = Json.writeSorted(payload).toByteArray().base64URL()
        val signingInput = "$headerB64.$payloadB64"
        val sig = Signature.getInstance("SHA256withECDSAinP1363Format").apply {
            initSign(privateKey)
            update(signingInput.toByteArray())
        }.sign() // already raw r‖s (64 bytes)
        return "$signingInput.${sig.base64URL()}"
    }

    public companion object {
        /** Generate a fresh P-256 key. */
        public operator fun invoke(): DPoPKey {
            val gen = java.security.KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp256r1"))
            val pair = gen.generateKeyPair()
            val s = (pair.private as ECPrivateKey).s
            return DPoPKey(unsigned32(s))
        }

        /** Reconstruct a key from persisted scalar bytes. */
        public fun fromRaw(rawRepresentation: ByteArray): DPoPKey = DPoPKey(rawRepresentation)

        /** The `htu` claim value: the URL without query or fragment. */
        public fun htu(url: String): String {
            val uri = URI(url)
            return URI(uri.scheme, uri.authority, uri.path, null, null).toString()
        }

        private fun p256Params(): java.security.spec.ECParameterSpec {
            val gen = java.security.KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp256r1"))
            return (gen.generateKeyPair().public as ECPublicKey).params
        }

        private fun derivePublicKey(
            s: BigInteger,
            params: java.security.spec.ECParameterSpec,
        ): ECPublicKey {
            val q = EcMath.scalarMultiply(s, params)
            val spec = ECPublicKeySpec(q, params)
            return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
        }

        internal fun unsigned32(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return when {
                bytes.size == 32 -> bytes
                bytes.size == 33 && bytes[0].toInt() == 0 -> bytes.copyOfRange(1, 33)
                bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
                else -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            }
        }
    }
}
