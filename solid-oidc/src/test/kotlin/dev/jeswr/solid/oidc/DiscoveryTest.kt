package dev.jeswr.solid.oidc

import dev.jeswr.solid.testsupport.MockHttpClient
import dev.jeswr.solid.testsupport.jsonResponse
import dev.jeswr.solid.testsupport.turtleResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI

class IssuerDiscoveryTest {
    private val webID = URI("https://pod.example/alice/profile/card#me")

    private fun profileClient(issuers: List<String>): MockHttpClient {
        val triples = issuers.joinToString("\n") { "<#me> solid:oidcIssuer <$it>." }
        return MockHttpClient().on("GET", "https://pod.example/alice/profile/card") { request ->
            turtleResponse(
                """
                @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                @prefix foaf: <http://xmlns.com/foaf/0.1/>.
                <#me> a foaf:Person.
                $triples
                """.trimIndent(),
                request.url,
            )
        }
    }

    @Test
    fun resolvesSingleIssuer() {
        val result = IssuerDiscovery.resolve(webID, profileClient(listOf("https://idp.example/")))
        assertEquals(URI("https://idp.example/"), result.issuer)
        assertEquals(webID, result.webID)
    }

    @Test
    fun multipleIssuersRequireChooser() {
        val client = profileClient(listOf("https://a.example/", "https://b.example/"))
        assertThrows<SolidOidcException> { IssuerDiscovery.resolve(webID, client) }
        val chosen = IssuerDiscovery.resolve(webID, client) { issuers ->
            assertEquals(2, issuers.size)
            issuers[1]
        }
        assertEquals(URI("https://b.example/"), chosen.issuer)
    }

    @Test
    fun webIDWithoutIssuerIsActionableError() {
        val client = profileClient(emptyList())
        val ex = assertThrows<SolidOidcException.NoOidcIssuer> { IssuerDiscovery.resolve(webID, client) }
        assertEquals(webID, ex.webID)
    }

    @Test
    fun nonRDFInputIsTreatedAsIssuer() {
        val issuer = URI("https://idp.example/")
        val client = MockHttpClient().on("GET", "https://idp.example/") { request ->
            HttpResponse(200, request.url, mapOf("Content-Type" to "text/html"), "<html>".toByteArray())
        }
        val result = IssuerDiscovery.resolve(issuer, client)
        assertEquals(issuer, result.issuer)
        assertNull(result.webID)
    }
}

class ProviderConfigurationTest {
    @Test
    fun discoversFromWellKnown() {
        val issuer = URI("https://idp.example")
        val client = MockHttpClient().on(
            "GET",
            "https://idp.example/.well-known/openid-configuration",
        ) { request ->
            jsonResponse(
                mapOf(
                    "issuer" to "https://idp.example/",
                    "authorization_endpoint" to "https://idp.example/authorize",
                    "token_endpoint" to "https://idp.example/token",
                    "registration_endpoint" to "https://idp.example/register",
                    "jwks_uri" to "https://idp.example/jwks",
                    "scopes_supported" to listOf("openid", "webid", "offline_access"),
                ),
                request.url,
            )
        }
        val config = ProviderConfiguration.discover(issuer, client)
        assertEquals(URI("https://idp.example/token"), config.tokenEndpoint)
        assertEquals(URI("https://idp.example/register"), config.registrationEndpoint)
        assertTrue(config.scopesSupported?.contains("webid") == true)
    }

    @Test
    fun non200IsTypedError() {
        val issuer = URI("https://not-an-idp.example/")
        assertThrows<SolidOidcException> {
            ProviderConfiguration.discover(issuer, MockHttpClient())
        }
    }
}
