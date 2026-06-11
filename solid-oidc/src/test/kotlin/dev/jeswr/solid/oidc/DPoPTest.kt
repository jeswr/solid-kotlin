package dev.jeswr.solid.oidc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

class DPoPTest {
    private val b64 = Base64.getUrlDecoder()

    private fun payloadOf(jwt: String): Map<String, Any?> =
        Json.parseObject(b64.decode(jwt.split(".")[1]).toString(Charsets.UTF_8))

    private fun headerOf(jwt: String): Map<String, Any?> =
        Json.parseObject(b64.decode(jwt.split(".")[0]).toString(Charsets.UTF_8))

    @Test
    fun proofStructureAndSignature() {
        val key = DPoPKey()
        val url = "https://pod.example/container/resource?x=1#frag"
        val proof = key.proof(method = "get", url = url, accessToken = "token-123")

        val segments = proof.split(".")
        assertEquals(3, segments.size)

        val header = headerOf(proof)
        assertEquals("ES256", header["alg"])
        assertEquals("dpop+jwt", header["typ"])
        @Suppress("UNCHECKED_CAST")
        val jwk = header["jwk"] as Map<String, String>
        assertEquals("EC", jwk["kty"])
        assertEquals("P-256", jwk["crv"])

        val payload = payloadOf(proof)
        assertEquals("GET", payload["htm"])
        // htu strips query and fragment (RFC 9449 §4.2).
        assertEquals("https://pod.example/container/resource", payload["htu"])
        assertNotNull(payload["jti"])
        assertNotNull(payload["iat"])
        val expectedAth = MessageDigest.getInstance("SHA-256")
            .digest("token-123".toByteArray()).base64URL()
        assertEquals(expectedAth, payload["ath"])
        assertNull(payload["nonce"])

        // Verify the ES256 signature against the embedded JWK.
        val params = run {
            val gen = java.security.KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp256r1"))
            (gen.generateKeyPair().public as java.security.interfaces.ECPublicKey).params
        }
        val x = BigInteger(1, b64.decode(jwk["x"]))
        val y = BigInteger(1, b64.decode(jwk["y"]))
        val pub = KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
        val signingInput = "${segments[0]}.${segments[1]}".toByteArray()
        val verifier = Signature.getInstance("SHA256withECDSAinP1363Format")
        verifier.initVerify(pub)
        verifier.update(signingInput)
        assertTrue(verifier.verify(b64.decode(segments[2])))
    }

    @Test
    fun proofsHaveUniqueJTI() {
        val key = DPoPKey()
        val url = "https://pod.example/"
        assertFalse(
            payloadOf(key.proof("GET", url))["jti"] == payloadOf(key.proof("GET", url))["jti"],
        )
    }

    @Test
    fun nonceIsEchoed() {
        val key = DPoPKey()
        val proof = key.proof(method = "POST", url = "https://idp.example/token", nonce = "server-nonce")
        assertEquals("server-nonce", payloadOf(proof)["nonce"])
    }

    @Test
    fun keyRoundTripsThroughRaw() {
        val key = DPoPKey()
        val restored = DPoPKey.fromRaw(key.rawRepresentation)
        assertTrue(restored.rawRepresentation.contentEquals(key.rawRepresentation))
        assertEquals(key.jwkThumbprint, restored.jwkThumbprint)
        // The reconstructed key signs proofs that still verify against its JWK.
        val proof = restored.proof("GET", "https://pod.example/")
        @Suppress("UNCHECKED_CAST")
        val jwk = headerOf(proof)["jwk"] as Map<String, String>
        val params = run {
            val gen = java.security.KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp256r1"))
            (gen.generateKeyPair().public as java.security.interfaces.ECPublicKey).params
        }
        val pub = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(
                ECPoint(BigInteger(1, b64.decode(jwk["x"])), BigInteger(1, b64.decode(jwk["y"]))),
                params,
            ),
        )
        val segments = proof.split(".")
        val verifier = Signature.getInstance("SHA256withECDSAinP1363Format")
        verifier.initVerify(pub)
        verifier.update("${segments[0]}.${segments[1]}".toByteArray())
        assertTrue(verifier.verify(b64.decode(segments[2])))
    }

    @Test
    fun rfc7638ThumbprintShape() {
        val key = DPoPKey()
        // 32 bytes base64url → 43 characters, URL-safe alphabet.
        assertEquals(43, key.jwkThumbprint.length)
        assertFalse(key.jwkThumbprint.contains("+") || key.jwkThumbprint.contains("/"))
    }
}

class PKCETest {
    @Test
    fun rfc7636TestVector() {
        val pkce = PKCE.fromVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkce.challenge)
    }

    @Test
    fun freshVerifierIsHighEntropy() {
        val pkce = PKCE()
        assertTrue(pkce.verifier.length >= 43)
        assertFalse(PKCE().verifier == pkce.verifier)
    }
}
