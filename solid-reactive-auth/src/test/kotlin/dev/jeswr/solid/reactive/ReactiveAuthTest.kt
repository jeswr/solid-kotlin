package dev.jeswr.solid.reactive

import dev.jeswr.solid.oidc.ClientCredentials
import dev.jeswr.solid.oidc.DPoPKey
import dev.jeswr.solid.oidc.HttpClient
import dev.jeswr.solid.oidc.HttpRequest
import dev.jeswr.solid.oidc.HttpResponse
import dev.jeswr.solid.oidc.SessionState
import dev.jeswr.solid.oidc.SolidSession
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

class ReactiveAuthTest {
    private lateinit var server: MockWebServer
    private val sessionRef = AtomicReference<SolidSession?>()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): OkHttpClient =
        installSolidReactiveAuth(OkHttpClient.Builder()) { sessionRef.get() }.build()

    private fun session(
        accessToken: String = "token-1",
        refreshToken: String? = "refresh-1",
        expiresAt: Long? = null,
        transport: HttpClient = HttpClient { error("no token endpoint in this test") },
    ): SolidSession = SolidSession.fromState(
        SessionState(
            issuer = URI("https://idp.example/"),
            webID = URI("https://pod.example/alice/profile/card#me"),
            tokenEndpoint = server.url("/token").toUri(),
            clientCredentials = ClientCredentials("client-1"),
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochMillis = expiresAt,
            dpopKey = DPoPKey(),
        ),
        httpClient = transport,
    )

    private fun payloadOf(jwt: String): String =
        Base64.getUrlDecoder().decode(jwt.split(".")[1]).toString(Charsets.UTF_8)

    @Test
    fun unauthenticatedWhenNoSession() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val response = client().newCall(Request.Builder().url(server.url("/public")).build()).execute()
        response.use { assertEquals(200, it.code) }
        val recorded = server.takeRequest()
        assertEquals(null, recorded.getHeader("Authorization"))
    }

    @Test
    fun signsRequestWithDPoPAndToken() {
        sessionRef.set(session())
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        client().newCall(Request.Builder().url(server.url("/notes.ttl")).build()).execute().close()

        val recorded = server.takeRequest()
        assertEquals("DPoP token-1", recorded.getHeader("Authorization"))
        val proof = recorded.getHeader("DPoP")!!
        val payload = payloadOf(proof)
        assertTrue(payload.contains("\"htm\":\"GET\""))
        assertTrue(payload.contains("\"ath\":"))
    }

    @Test
    fun retriesWithServerNonce() {
        sessionRef.set(session())
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", "DPoP error=\"use_dpop_nonce\"")
                .setHeader("DPoP-Nonce", "nonce-abc"),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = client().newCall(Request.Builder().url(server.url("/notes.ttl")).build()).execute()
        response.use { assertEquals(200, it.code) }

        server.takeRequest() // first (no nonce)
        val second = server.takeRequest()
        assertTrue(payloadOf(second.getHeader("DPoP")!!).contains("\"nonce\":\"nonce-abc\""))
    }

    @Test
    fun authenticatorRefreshesOn401AndReplays() {
        // The session's token endpoint (on MockWebServer) returns a fresh token.
        val transport = okHttpTransport()
        sessionRef.set(session(accessToken = "stale", transport = transport))

        // 1st protected call → 401; refresh token call → new token; replay → 200.
        server.enqueue(MockResponse().setResponseCode(401)) // protected resource rejects stale
        server.enqueue( // token endpoint
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"token-2","token_type":"DPoP","refresh_token":"refresh-2","expires_in":600}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok")) // replay succeeds

        val response = client().newCall(Request.Builder().url(server.url("/notes.ttl")).build()).execute()
        response.use { assertEquals(200, it.code) }

        val first = server.takeRequest()
        assertEquals("DPoP stale", first.getHeader("Authorization"))
        val tokenCall = server.takeRequest()
        assertTrue(tokenCall.body.readUtf8().contains("grant_type=refresh_token"))
        val replay = server.takeRequest()
        assertEquals("DPoP token-2", replay.getHeader("Authorization"))
        assertNotNull(replay.getHeader("DPoP"))
    }

    /** A plain OkHttp transport for the session's bootstrap (token) calls. */
    private fun okHttpTransport(): HttpClient {
        val plain = OkHttpClient()
        return HttpClient { request ->
            val builder = Request.Builder().url(request.url)
            val body = request.body?.let {
                okhttp3.RequestBody.create(null as okhttp3.MediaType?, it)
            }
            builder.method(request.method, body)
            request.headers.forEach { (k, v) -> builder.header(k, v) }
            plain.newCall(builder.build()).execute().use { r ->
                val headers = LinkedHashMap<String, String>()
                for (i in 0 until r.headers.size) headers[r.headers.name(i)] = r.headers.value(i)
                HttpResponse(r.code, r.request.url.toString(), headers, r.body?.bytes() ?: ByteArray(0))
            }
        }
    }
}
