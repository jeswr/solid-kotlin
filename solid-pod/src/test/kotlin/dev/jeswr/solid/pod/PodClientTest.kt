package dev.jeswr.solid.pod

import dev.jeswr.solid.oidc.HttpResponse
import dev.jeswr.solid.rdf.Graph
import dev.jeswr.solid.rdf.Term
import dev.jeswr.solid.rdf.Triple
import dev.jeswr.solid.testsupport.MockHttpClient
import dev.jeswr.solid.testsupport.turtleResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI

class PodClientTest {
    private val resource = URI("https://pod.example/alice/notes/note1.ttl")

    @Test
    fun readParsesTurtleAndKeepsETag() {
        val http = MockHttpClient().on("GET", "https://pod.example/") { request ->
            assertEquals("text/turtle", request.headers["Accept"])
            turtleResponse("<> <https://example.org/title> \"Note 1\".", request.url, etag = "\"v1\"")
        }
        val read = SolidPodClient(http).readResource(resource)
        assertTrue(read.exists)
        assertEquals("\"v1\"", read.etag)
        assertEquals(
            Term.string("Note 1"),
            read.graph.firstObject(Term.IRI(resource.toString()), Term.IRI("https://example.org/title")),
        )
    }

    @Test
    fun read404ResolvesAsNotExists() {
        val read = SolidPodClient(MockHttpClient()).readResource(resource)
        assertFalse(read.exists)
        assertNull(read.etag)
        assertTrue(read.graph.isEmpty)
    }

    @Test
    fun read401And403AreTyped() {
        for ((status, isAuth) in listOf(401 to true, 403 to false)) {
            val http = MockHttpClient().on("GET", "https://pod.example/") { request ->
                HttpResponse(status, request.url, emptyMap(), ByteArray(0))
            }
            val ex = assertThrows<PodException> { SolidPodClient(http).readResource(resource) }
            if (isAuth) assertTrue(ex is PodException.AuthenticationRequired)
            else assertTrue(ex is PodException.Forbidden)
        }
    }

    @Test
    fun writeWithETagUsesIfMatch() {
        val http = MockHttpClient().on("PUT", "https://pod.example/") { request ->
            assertEquals("\"v1\"", request.headers["If-Match"])
            assertNull(request.headers["If-None-Match"])
            assertEquals("text/turtle", request.headers["Content-Type"])
            HttpResponse(205, request.url, mapOf("ETag" to "\"v2\""), ByteArray(0))
        }
        val graph = Graph.of(
            Triple(resource.toString(), "https://example.org/title", Term.string("Updated")),
        )
        val result = SolidPodClient(http).write(graph, resource, ifMatch = "\"v1\"")
        assertEquals("\"v2\"", result.etag)
    }

    @Test
    fun writeWithoutETagAssertsCreate() {
        val http = MockHttpClient().on("PUT", "https://pod.example/") { request ->
            assertEquals("*", request.headers["If-None-Match"])
            assertNull(request.headers["If-Match"])
            HttpResponse(201, request.url, emptyMap(), ByteArray(0))
        }
        SolidPodClient(http).write(Graph(), resource)
    }

    @Test
    fun staleETagSurfacesAsPreconditionFailed() {
        val http = MockHttpClient().on("PUT", "https://pod.example/") { request ->
            HttpResponse(412, request.url, emptyMap(), ByteArray(0))
        }
        assertThrows<PodException.PreconditionFailed> {
            SolidPodClient(http).write(Graph(), resource, ifMatch = "\"stale\"")
        }
    }

    @Test
    fun deleteSucceedsAndMapsErrors() {
        val http = MockHttpClient().on("DELETE", "https://pod.example/") { request ->
            HttpResponse(205, request.url, emptyMap(), ByteArray(0))
        }
        SolidPodClient(http).delete(resource)
        assertThrows<PodException.NotFound> { SolidPodClient(MockHttpClient()).delete(resource) }
    }

    @Test
    fun listContainerParsesMembers() {
        val container = URI("https://pod.example/alice/notes/")
        val http = MockHttpClient().on("GET", container.toString()) { request ->
            turtleResponse(
                """
                @prefix ldp: <http://www.w3.org/ns/ldp#>.
                <> a ldp:BasicContainer;
                   ldp:contains <note1.ttl>, <note2.ttl>, <sub/>.
                """.trimIndent(),
                request.url,
                etag = "\"c1\"",
            )
        }
        val listing = SolidPodClient(http).listContainer(container)
        assertEquals(
            listOf(
                "https://pod.example/alice/notes/note1.ttl",
                "https://pod.example/alice/notes/note2.ttl",
                "https://pod.example/alice/notes/sub/",
            ),
            listing.members.map { it.toString() },
        )
        assertEquals("\"c1\"", listing.etag)
    }

    @Test
    fun listContainerRejectsNonContainerURL() {
        assertThrows<PodException> { SolidPodClient(MockHttpClient()).listContainer(resource) }
    }

    @Test
    fun ensureContainerCreatesWhenAbsent() {
        val container = URI("https://pod.example/alice/new/")
        val http = MockHttpClient()
            .on("HEAD", container.toString()) { request ->
                HttpResponse(404, request.url, emptyMap(), ByteArray(0))
            }
            .on("PUT", container.toString()) { request ->
                assertEquals("*", request.headers["If-None-Match"])
                assertTrue(request.headers["Link"]?.contains("BasicContainer") == true)
                HttpResponse(201, request.url, emptyMap(), ByteArray(0))
            }
        assertTrue(SolidPodClient(http).ensureContainer(container))
    }

    @Test
    fun ensureContainerIsIdempotentAndRaceSafe() {
        val container = URI("https://pod.example/alice/notes/")
        val exists = MockHttpClient().on("HEAD", container.toString()) { request ->
            HttpResponse(200, request.url, emptyMap(), ByteArray(0))
        }
        assertFalse(SolidPodClient(exists).ensureContainer(container))

        val raced = MockHttpClient()
            .on("HEAD", container.toString()) { request -> HttpResponse(404, request.url, emptyMap(), ByteArray(0)) }
            .on("PUT", container.toString()) { request -> HttpResponse(412, request.url, emptyMap(), ByteArray(0)) }
        assertFalse(SolidPodClient(raced).ensureContainer(container))
    }
}

class ProfileAndStorageTest {
    private val webID = URI("https://pod.example/alice/profile/card#me")

    @Test
    fun profileConveniences() {
        val http = MockHttpClient().on("GET", "https://pod.example/alice/profile/card") { request ->
            turtleResponse(
                """
                @prefix foaf: <http://xmlns.com/foaf/0.1/>.
                @prefix pim: <http://www.w3.org/ns/pim/space#>.
                @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                <#me> foaf:name "Alice"; foaf:name "Alicia"@es;
                    pim:storage <https://pod.example/alice/>;
                    solid:oidcIssuer <https://idp.example/>.
                """.trimIndent(),
                request.url,
            )
        }
        val profile = SolidPodClient(http).profile(webID)
        assertEquals("Alice", profile.name) // prefers the untagged literal
        assertEquals(listOf(URI("https://pod.example/alice/")), profile.storages)
        assertEquals(listOf(URI("https://idp.example/")), profile.oidcIssuers)
    }

    @Test
    fun discoverStorageFromProfile() {
        val http = MockHttpClient().on("GET", "https://pod.example/alice/profile/card") { request ->
            turtleResponse(
                """
                @prefix pim: <http://www.w3.org/ns/pim/space#>.
                <#me> pim:storage <https://pod.example/alice/>.
                """.trimIndent(),
                request.url,
            )
        }
        val discovery = SolidPodClient(http).discoverStorage(webID)
        assertEquals(StorageSource.PROFILE, discovery.source)
        assertEquals(listOf(URI("https://pod.example/alice/")), discovery.storages)
    }

    @Test
    fun discoverStorageFallsBackToLinkHeaderWalk() {
        val http = MockHttpClient()
            .on("GET", "https://pod.example/alice/profile/card") { request ->
                turtleResponse("<#me> a <http://xmlns.com/foaf/0.1/Person>.", request.url)
            }
            .on("HEAD", "https://pod.example/") { request ->
                if (request.url == "https://pod.example/alice/") {
                    HttpResponse(
                        200,
                        request.url,
                        mapOf("Link" to "<http://www.w3.org/ns/pim/space#Storage>; rel=\"type\""),
                        ByteArray(0),
                    )
                } else {
                    HttpResponse(200, request.url, emptyMap(), ByteArray(0))
                }
            }
        val discovery = SolidPodClient(http).discoverStorage(webID)
        assertEquals(StorageSource.LINK_HEADER, discovery.source)
        assertEquals(listOf(URI("https://pod.example/alice/")), discovery.storages)
    }

    @Test
    fun noStorageAnywhereIsTyped() {
        val http = MockHttpClient()
            .on("GET", "https://pod.example/alice/profile/card") { request ->
                turtleResponse("<#me> a <http://xmlns.com/foaf/0.1/Person>.", request.url)
            }
            .on("HEAD", "https://pod.example/") { request ->
                HttpResponse(200, request.url, emptyMap(), ByteArray(0))
            }
        assertThrows<PodException.StorageNotFound> { SolidPodClient(http).discoverStorage(webID) }
    }
}

class LinkHeaderTest {
    private val base = URI("https://pod.example/alice/notes/note1.ttl")

    @Test
    fun parsesAclAndTypeRels() {
        val header = "<note1.ttl.acl>; rel=\"acl\", <http://www.w3.org/ns/ldp#Resource>; rel=\"type\""
        assertEquals(
            listOf("https://pod.example/alice/notes/note1.ttl.acl"),
            LinkHeader.targets(header, rel = "acl", base = base).map { it.toString() },
        )
        assertEquals(
            listOf("http://www.w3.org/ns/ldp#Resource"),
            LinkHeader.targets(header, rel = "type", base = base).map { it.toString() },
        )
    }

    @Test
    fun handlesQuotedCommasAndMultipleRels() {
        val header = "<https://x.example/a>; rel=\"alternate acl\"; title=\"x, y\", <https://x.example/b>; rel=acl"
        assertEquals(
            listOf("https://x.example/a", "https://x.example/b"),
            LinkHeader.targets(header, rel = "acl", base = base).map { it.toString() },
        )
    }

    @Test
    fun nilAndGarbageAreEmpty() {
        assertTrue(LinkHeader.targets(null, rel = "acl", base = base).isEmpty())
        assertTrue(LinkHeader.targets("garbage", rel = "acl", base = base).isEmpty())
    }
}
