package dev.jeswr.solid.oidc

import dev.jeswr.solid.testsupport.MockHttpClient
import dev.jeswr.solid.testsupport.jsonResponse
import dev.jeswr.solid.testsupport.turtleResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class AuthFlowTest {
    private val webID = URI("https://pod.example/alice/profile/card#me")
    private val redirectURI = URI("solidtest://oauth/callback")

    private fun mockProvider(): MockHttpClient =
        MockHttpClient()
            .on("GET", "https://pod.example/alice/profile/card") { request ->
                turtleResponse(
                    """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    <#me> solid:oidcIssuer <https://idp.example/>.
                    """.trimIndent(),
                    request.url,
                )
            }
            .on("GET", "https://idp.example/.well-known/openid-configuration") { request ->
                jsonResponse(
                    mapOf(
                        "issuer" to "https://idp.example/",
                        "authorization_endpoint" to "https://idp.example/authorize",
                        "token_endpoint" to "https://idp.example/token",
                        "registration_endpoint" to "https://idp.example/register",
                    ),
                    request.url,
                )
            }
            .on("POST", "https://idp.example/register") { request ->
                val metadata = Json.parseObject(request.body!!.toString(Charsets.UTF_8))
                @Suppress("UNCHECKED_CAST")
                assertTrue((metadata["redirect_uris"] as List<String>).contains("solidtest://oauth/callback"))
                jsonResponse(mapOf("client_id" to "registered-client"), request.url, status = 201)
            }
            .on("POST", "https://idp.example/token") { request ->
                assertNotNull(request.headers["DPoP"])
                val form = request.body!!.toString(Charsets.UTF_8)
                assertTrue(form.contains("grant_type=authorization_code"))
                assertTrue(form.contains("code=test-code"))
                assertTrue(form.contains("code_verifier="))
                assertTrue(form.contains("client_id=registered-client"))
                jsonResponse(
                    mapOf(
                        "access_token" to makeUnsignedJWT(
                            mapOf("webid" to webID.toString(), "exp" to System.currentTimeMillis() / 1000 + 600),
                        ),
                        "token_type" to "DPoP",
                        "refresh_token" to "refresh-1",
                        "expires_in" to 600,
                    ),
                    request.url,
                )
            }

    @Test
    fun fullLoginFlow() {
        val store = InMemorySessionStore()
        val auth = SolidAuthClient(
            SolidAuthConfiguration(redirectURI),
            httpClient = mockProvider(),
            userAgent = StubUserAgent(),
            sessionStore = store,
        )
        val session = auth.logIn(webID)
        assertEquals(webID, session.webID)
        assertEquals(URI("https://idp.example/"), session.issuer)
        assertNotNull(store.load("default"))
    }

    @Test
    fun restoreSessionRoundTrip() {
        val store = InMemorySessionStore()
        val auth = SolidAuthClient(
            SolidAuthConfiguration(redirectURI),
            httpClient = mockProvider(),
            userAgent = StubUserAgent(),
            sessionStore = store,
        )
        auth.logIn(webID)
        assertEquals(webID, auth.restoreSession()?.webID)
        auth.logOut()
        assertNull(auth.restoreSession())
    }

    @Test
    fun stateMismatchIsRejected() {
        val evil = AuthorizationUserAgent { _, redirect -> URI("$redirect?code=stolen&state=wrong") }
        val auth = SolidAuthClient(
            SolidAuthConfiguration(redirectURI),
            httpClient = mockProvider(),
            userAgent = evil,
            sessionStore = InMemorySessionStore(),
        )
        assertThrows<SolidOidcException.StateMismatch> { auth.logIn(webID) }
    }

    @Test
    fun userCancellationSurfacesAsLoginCancelled() {
        val cancelling = AuthorizationUserAgent { _, _ -> throw SolidOidcException.LoginCancelled() }
        val auth = SolidAuthClient(
            SolidAuthConfiguration(redirectURI),
            httpClient = mockProvider(),
            userAgent = cancelling,
            sessionStore = InMemorySessionStore(),
        )
        assertThrows<SolidOidcException.LoginCancelled> { auth.logIn(webID) }
    }

    @Test
    fun clientIDDocumentModeSkipsRegistration() {
        val http = mockProvider()
        val clientID = URI("https://app.example/clientid.jsonld")
        val auth = SolidAuthClient(
            SolidAuthConfiguration(redirectURI, client = ClientConfiguration.ClientIDDocument(clientID)),
            httpClient = http,
            userAgent = StubUserAgent(),
            sessionStore = InMemorySessionStore(),
        )
        http.on("POST", "https://idp.example/token") { request ->
            val form = request.body!!.toString(Charsets.UTF_8)
            assertTrue(form.contains("client_id=" + TokenClient.formEncode(clientID.toString())))
            jsonResponse(
                mapOf(
                    "access_token" to makeUnsignedJWT(mapOf("webid" to webID.toString())),
                    "token_type" to "DPoP",
                ),
                request.url,
            )
        }
        auth.logIn(webID)
        val registrations = http.recordedRequests().filter { it.url.contains("/register") }
        assertTrue(registrations.isEmpty())
    }
}

class SessionTest {
    private val resource = "https://pod.example/alice/notes.ttl"

    private fun makeSession(
        http: MockHttpClient,
        accessToken: String = "token-1",
        refreshToken: String? = "refresh-1",
        expiresAt: Long? = null,
    ): SolidSession = SolidSession(
        SessionState(
            issuer = URI("https://idp.example/"),
            webID = URI("https://pod.example/alice/profile/card#me"),
            tokenEndpoint = URI("https://idp.example/token"),
            clientCredentials = ClientCredentials("client-1"),
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochMillis = expiresAt,
            dpopKey = DPoPKey(),
        ),
        httpClient = http,
    )

    private fun payloadOf(jwt: String): Map<String, Any?> =
        Json.parseObject(java.util.Base64.getUrlDecoder().decode(jwt.split(".")[1]).toString(Charsets.UTF_8))

    @Test
    fun injectsAuthorizationAndProof() {
        val http = MockHttpClient().on("GET", "https://pod.example/") { request ->
            assertEquals("DPoP token-1", request.headers["Authorization"])
            val payload = payloadOf(request.headers["DPoP"]!!)
            assertEquals("GET", payload["htm"])
            assertEquals("https://pod.example/alice/notes.ttl", payload["htu"])
            assertNotNull(payload["ath"])
            HttpResponse(200, request.url, emptyMap(), ByteArray(0))
        }
        val response = makeSession(http).send(HttpRequest(url = resource))
        assertTrue(response.isSuccess)
    }

    @Test
    fun retriesWithServerNonce() {
        val counter = AtomicInteger(0)
        val http = MockHttpClient().on("GET", "https://pod.example/") { request ->
            if (counter.incrementAndGet() == 1) {
                assertTrue(request.headers["DPoP"]!!.isNotEmpty())
                HttpResponse(
                    401,
                    request.url,
                    mapOf(
                        "WWW-Authenticate" to "DPoP error=\"use_dpop_nonce\"",
                        "DPoP-Nonce" to "nonce-abc",
                    ),
                    ByteArray(0),
                )
            } else {
                val payload = payloadOf(request.headers["DPoP"]!!)
                assertEquals("nonce-abc", payload["nonce"])
                HttpResponse(200, request.url, emptyMap(), ByteArray(0))
            }
        }
        val response = makeSession(http).send(HttpRequest(url = resource))
        assertEquals(200, response.statusCode)
        assertEquals(2, counter.get())
    }

    @Test
    fun refreshesExpiredTokenBeforeSending() {
        val http = MockHttpClient()
            .on("POST", "https://idp.example/token") { request ->
                val form = request.body!!.toString(Charsets.UTF_8)
                assertTrue(form.contains("grant_type=refresh_token"))
                assertTrue(form.contains("refresh_token=refresh-1"))
                jsonResponse(
                    mapOf(
                        "access_token" to "token-2",
                        "token_type" to "DPoP",
                        "refresh_token" to "refresh-2",
                        "expires_in" to 600,
                    ),
                    request.url,
                )
            }
            .on("GET", "https://pod.example/") { request ->
                assertEquals("DPoP token-2", request.headers["Authorization"])
                HttpResponse(200, request.url, emptyMap(), ByteArray(0))
            }
        val session = makeSession(http, expiresAt = System.currentTimeMillis() - 10_000)
        val response = session.send(HttpRequest(url = resource))
        assertTrue(response.isSuccess)
    }

    @Test
    fun expiredWithoutRefreshTokenThrows() {
        val session = makeSession(
            MockHttpClient(),
            refreshToken = null,
            expiresAt = System.currentTimeMillis() - 10_000,
        )
        assertThrows<SolidOidcException> { session.currentAccessToken() }
    }
}
