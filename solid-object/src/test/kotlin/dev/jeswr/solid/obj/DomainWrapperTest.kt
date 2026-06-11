package dev.jeswr.solid.obj

import dev.jeswr.solid.rdf.Turtle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProfileAgentTest {
    private val webID = "https://alice.example/profile/card#me"

    private fun profile(turtle: String): ProfileAgent =
        ProfileAgent(webID, GraphBox(Turtle.parse(turtle)))

    @Test
    fun nameUsesFoafFirst() {
        val me = profile(
            """
            @prefix foaf: <http://xmlns.com/foaf/0.1/> .
            @prefix schema: <https://schema.org/> .
            <$webID> foaf:name "Alice Foaf" ; schema:name "Alice Schema" .
            """.trimIndent(),
        )
        assertEquals("Alice Foaf", me.name)
    }

    @Test
    fun nameFallsBackThroughChain() {
        val me = profile(
            """
            @prefix vcard: <http://www.w3.org/2006/vcard/ns#> .
            <$webID> vcard:fn "Alice Vcard" .
            """.trimIndent(),
        )
        assertEquals("Alice Vcard", me.name)
    }

    @Test
    fun displayNameFallsBackToWebID() {
        val me = profile("<$webID> <https://example.org/unrelated> \"x\" .")
        assertNull(me.name)
        assertEquals(webID, me.displayName)
    }

    @Test
    fun photoReadsAcrossPredicates() {
        val me = profile(
            """
            @prefix foaf: <http://xmlns.com/foaf/0.1/> .
            <$webID> foaf:img <https://alice.example/me.jpg> .
            """.trimIndent(),
        )
        assertEquals("https://alice.example/me.jpg", me.photo?.toString())
    }

    @Test
    fun emailStripsMailto() {
        val me = profile(
            """
            @prefix foaf: <http://xmlns.com/foaf/0.1/> .
            <$webID> foaf:mbox <mailto:alice@example.org> .
            """.trimIndent(),
        )
        assertEquals("alice@example.org", me.email)
    }

    @Test
    fun emailFollowsVcardValueNode() {
        val me = profile(
            """
            @prefix vcard: <http://www.w3.org/2006/vcard/ns#> .
            <$webID> vcard:hasEmail <https://alice.example/email> .
            <https://alice.example/email> vcard:value <mailto:alice@work.example> .
            """.trimIndent(),
        )
        assertEquals("alice@work.example", me.email)
    }

    @Test
    fun storageUrlsAndOidcIssuer() {
        val me = profile(
            """
            @prefix pim: <http://www.w3.org/ns/pim/space#> .
            @prefix solid: <http://www.w3.org/ns/solid/terms#> .
            <$webID> pim:storage <https://alice.example/> ;
                     solid:oidcIssuer <https://idp.example/> .
            """.trimIndent(),
        )
        assertEquals(setOf("https://alice.example/"), me.storageUrls.map { it.toString() }.toSet())
        assertEquals("https://idp.example/", me.oidcIssuer?.toString())
    }

    @Test
    fun knowsProjectsNestedAgents() {
        val me = profile(
            """
            @prefix foaf: <http://xmlns.com/foaf/0.1/> .
            <$webID> foaf:knows <https://bob.example/#me> .
            <https://bob.example/#me> foaf:name "Bob" .
            """.trimIndent(),
        )
        assertEquals(1, me.knows.size)
        assertEquals("Bob", me.knows.first?.name)
    }

    @Test
    fun setNameMutatesGraph() {
        val me = profile("<$webID> <https://example.org/x> \"y\" .")
        me.setName("Renamed")
        assertEquals("Renamed", me.name)
    }
}

class LDPContainerTest {
    private val base = "https://alice.example/notes/"

    private fun container(turtle: String): LDPContainer =
        LDPContainer(base, GraphBox(Turtle.parse(turtle)))

    @Test
    fun listsMembersWithKind() {
        val c = container(
            """
            @prefix ldp: <http://www.w3.org/ns/ldp#> .
            <$base> a ldp:Container ;
                ldp:contains <${base}one.ttl>, <${base}sub/> .
            <${base}sub/> a ldp:Container .
            """.trimIndent(),
        )
        assertEquals(true, c.isContainer)
        assertEquals(2, c.contains.size)
        val byName = c.contains.associateBy { it.name ?: "?" }
        assertEquals(false, byName["one.ttl"]?.isContainer)
        assertEquals(true, byName["sub"]?.isContainer)
        assertFalse(byName.isEmpty())
    }

    @Test
    fun decodesPercentEncodedName() {
        val c = container(
            """
            @prefix ldp: <http://www.w3.org/ns/ldp#> .
            <$base> ldp:contains <${base}my%20note.ttl> .
            """.trimIndent(),
        )
        assertEquals("my note.ttl", c.contains.first?.name)
    }
}
