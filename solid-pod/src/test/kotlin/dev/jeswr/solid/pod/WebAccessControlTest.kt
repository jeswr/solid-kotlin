package dev.jeswr.solid.pod

import dev.jeswr.solid.oidc.HttpResponse
import dev.jeswr.solid.rdf.Graph
import dev.jeswr.solid.rdf.Term
import dev.jeswr.solid.rdf.Turtle
import dev.jeswr.solid.testsupport.MockHttpClient
import dev.jeswr.solid.testsupport.turtleResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI

/** A WAC fixture: an in-memory "server" of resources + their advertised ACL URLs. */
private class WacServer {
    val http = MockHttpClient()
    private val documents = HashMap<String, String>()
    private val lock = Any()

    init {
        http.on("HEAD", "https://pod.example/") { request ->
            val url = request.url
            val status = if (body(url) != null || url.endsWith("/")) 200 else 404
            HttpResponse(status, request.url, mapOf("Link" to "<$url.acl>; rel=\"acl\""), ByteArray(0))
        }
        http.on("GET", "https://pod.example/") { request ->
            val body = body(request.url)
                ?: return@on HttpResponse(404, request.url, emptyMap(), ByteArray(0))
            turtleResponse(body, request.url, etag = "\"v1\"")
        }
        http.on("PUT", "https://pod.example/") { request ->
            put(request.url, request.body!!.toString(Charsets.UTF_8))
            HttpResponse(201, request.url, emptyMap(), ByteArray(0))
        }
    }

    fun seed(url: String, turtle: String) = synchronized(lock) { documents[url] = turtle }
    fun body(url: String): String? = synchronized(lock) { documents[url] }
    private fun put(url: String, body: String) = synchronized(lock) { documents[url] = body }
    fun graph(url: String): Graph = Turtle.parse(body(url) ?: "", base = url)
}

class WebAccessControlTest {
    private val owner = URI("https://pod.example/alice/profile/card#me")
    private val bob = URI("https://other.example/bob/profile/card#me")
    private val resource = URI("https://pod.example/alice/notes/note1.ttl")

    private fun rootACL(owner: String): String =
        """
        @prefix acl: <http://www.w3.org/ns/auth/acl#>.
        <#owner> a acl:Authorization;
            acl:agent <$owner>;
            acl:accessTo <https://pod.example/alice/>;
            acl:default <https://pod.example/alice/>;
            acl:mode acl:Read, acl:Write, acl:Control.
        """.trimIndent()

    @Test
    fun grantOnResourceWithOwnACL() {
        val server = WacServer()
        server.seed(
            "https://pod.example/alice/notes/note1.ttl.acl",
            """
            @prefix acl: <http://www.w3.org/ns/auth/acl#>.
            <#owner> a acl:Authorization;
                acl:agent <$owner>;
                acl:accessTo <$resource>;
                acl:mode acl:Read, acl:Write, acl:Control.
            """.trimIndent(),
        )
        val wac = WebAccessControl(server.http)
        wac.grantRead(resource, bob)

        val grants = wac.grants(resource)
        assertTrue(grants.contains(GrantEntry(bob, listOf(AccessMode.READ))))
        assertTrue(grants.contains(GrantEntry(owner, listOf(AccessMode.READ, AccessMode.WRITE, AccessMode.CONTROL))))
    }

    @Test
    fun grantMaterialisesInheritedDefaults() {
        val server = WacServer()
        server.seed("https://pod.example/alice/.acl", rootACL(owner.toString()))
        val wac = WebAccessControl(server.http)
        wac.grant(listOf(AccessMode.READ, AccessMode.WRITE), resource, bob)

        val own = server.graph("https://pod.example/alice/notes/note1.ttl.acl")
        assertFalse(own.isEmpty)
        val grants = wac.grants(resource)
        assertEquals(
            listOf(
                GrantEntry(bob, listOf(AccessMode.READ, AccessMode.WRITE)),
                GrantEntry(owner, listOf(AccessMode.READ, AccessMode.WRITE, AccessMode.CONTROL)),
            ).sortedBy { it.agent.toString() },
            grants,
        )
    }

    @Test
    fun grantFailsClosedWithNoACLAnywhere() {
        val server = WacServer()
        val wac = WebAccessControl(server.http)
        assertThrows<PodException.AclDiscoveryFailed> { wac.grantRead(resource, bob) }
    }

    @Test
    fun grantsFallBackToAncestorDefaults() {
        val server = WacServer()
        server.seed("https://pod.example/alice/.acl", rootACL(owner.toString()))
        val wac = WebAccessControl(server.http)
        val grants = wac.grants(resource)
        assertEquals(
            listOf(GrantEntry(owner, listOf(AccessMode.READ, AccessMode.WRITE, AccessMode.CONTROL))),
            grants,
        )
    }

    @Test
    fun revokeRemovesAgentAndEmptyRules() {
        val server = WacServer()
        val aclURL = "https://pod.example/alice/notes/note1.ttl.acl"
        server.seed(
            aclURL,
            """
            @prefix acl: <http://www.w3.org/ns/auth/acl#>.
            <#owner> a acl:Authorization;
                acl:agent <$owner>;
                acl:accessTo <$resource>;
                acl:mode acl:Read, acl:Write, acl:Control.
            <#bob> a acl:Authorization;
                acl:agent <$bob>;
                acl:accessTo <$resource>;
                acl:mode acl:Read.
            """.trimIndent(),
        )
        val wac = WebAccessControl(server.http)
        assertTrue(wac.revoke(bob, resource))

        val grants = wac.grants(resource)
        assertEquals(
            listOf(GrantEntry(owner, listOf(AccessMode.READ, AccessMode.WRITE, AccessMode.CONTROL))),
            grants,
        )
        val graph = server.graph(aclURL)
        assertTrue(graph.triples(subject = Term.IRI("$aclURL#bob")).isEmpty())
        assertFalse(wac.revoke(bob, resource))
    }

    @Test
    fun revokeCutsInheritedAccessByMaterialising() {
        val server = WacServer()
        val acl = rootACL(owner.toString()) +
            """

            <#bob> a <http://www.w3.org/ns/auth/acl#Authorization>;
                <http://www.w3.org/ns/auth/acl#agent> <$bob>;
                <http://www.w3.org/ns/auth/acl#default> <https://pod.example/alice/>;
                <http://www.w3.org/ns/auth/acl#accessTo> <https://pod.example/alice/>;
                <http://www.w3.org/ns/auth/acl#mode> <http://www.w3.org/ns/auth/acl#Read>.
            """.trimIndent()
        server.seed("https://pod.example/alice/.acl", acl)
        val wac = WebAccessControl(server.http)
        assertTrue(wac.revoke(bob, resource))
        val grants = wac.grants(resource)
        assertEquals(
            listOf(GrantEntry(owner, listOf(AccessMode.READ, AccessMode.WRITE, AccessMode.CONTROL))),
            grants,
        )
    }

    @Test
    fun acpDocumentsAreRejected() {
        val server = WacServer()
        server.seed(
            "https://pod.example/alice/notes/note1.ttl.acl",
            """
            @prefix acp: <http://www.w3.org/ns/solid/acp#>.
            <> acp:resource <$resource>.
            """.trimIndent(),
        )
        val wac = WebAccessControl(server.http)
        assertThrows<PodException.AcpNotSupported> { wac.grants(resource) }
    }

    @Test
    fun allGrantsWalksTheTree() {
        val server = WacServer()
        val root = URI("https://pod.example/alice/")
        server.seed("https://pod.example/alice/.acl", rootACL(owner.toString()))
        server.seed(
            "https://pod.example/alice/",
            """
            @prefix ldp: <http://www.w3.org/ns/ldp#>.
            <> ldp:contains <notes/>.
            """.trimIndent(),
        )
        server.seed(
            "https://pod.example/alice/notes/",
            """
            @prefix ldp: <http://www.w3.org/ns/ldp#>.
            <> ldp:contains <note1.ttl>.
            """.trimIndent(),
        )
        server.seed("https://pod.example/alice/notes/note1.ttl", "<#x> a <https://example.org/Note>.")
        server.seed(
            "https://pod.example/alice/notes/note1.ttl.acl",
            """
            @prefix acl: <http://www.w3.org/ns/auth/acl#>.
            <#bob> a acl:Authorization;
                acl:agent <$bob>;
                acl:accessTo <$resource>;
                acl:mode acl:Read.
            """.trimIndent(),
        )
        val wac = WebAccessControl(server.http)
        val all = wac.allGrants(root)
        assertEquals(2, all.size)
        assertEquals(
            listOf(GrantEntry(owner, listOf(AccessMode.READ, AccessMode.WRITE, AccessMode.CONTROL))),
            all.first { it.resource == root }.grants,
        )
        assertEquals(
            listOf(GrantEntry(bob, listOf(AccessMode.READ))),
            all.first { it.resource == resource }.grants,
        )
    }
}
