package dev.jeswr.solid.reactive

import dev.jeswr.solid.oidc.HttpRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OkHttpClientAdapterTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Solid servers send several `Link:` field lines on one response (acl, type,
     * describedby). The adapter must fold them into one comma-joined value rather
     * than letting the last line win — otherwise ACL/storage discovery, which
     * reads `Link rel="acl"`/`rel="type"`, silently sees nothing. Regression test
     * for the header-collapse bug.
     */
    @Test
    fun foldsRepeatedLinkHeaders() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Link", "<card.acl>; rel=\"acl\"")
                .addHeader("Link", "<http://www.w3.org/ns/pim/space#Storage>; rel=\"type\"")
                .addHeader("Link", "<card.meta>; rel=\"describedby\""),
        )

        val adapter = OkHttpClientAdapter()
        val response = adapter.send(HttpRequest("HEAD", server.url("/card").toString()))

        val link = response.header("Link")!!
        assertTrue(link.contains("rel=\"acl\""), "acl link survived folding: $link")
        assertTrue(link.contains("rel=\"type\""), "type link survived folding: $link")
        assertTrue(link.contains("rel=\"describedby\""), "describedby link survived folding: $link")
    }

    @Test
    fun singleHeaderIsUnchanged() {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("ETag", "\"abc\""))
        val adapter = OkHttpClientAdapter()
        val response = adapter.send(HttpRequest("HEAD", server.url("/x").toString()))
        assertEquals("\"abc\"", response.header("ETag"))
    }
}
