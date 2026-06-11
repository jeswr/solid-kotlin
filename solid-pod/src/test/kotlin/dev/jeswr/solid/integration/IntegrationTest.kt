package dev.jeswr.solid.integration

import dev.jeswr.solid.oidc.DynamicRegistration
import dev.jeswr.solid.oidc.IssuerDiscovery
import dev.jeswr.solid.oidc.ProviderConfiguration
import dev.jeswr.solid.pod.AccessMode
import dev.jeswr.solid.pod.PodException
import dev.jeswr.solid.pod.SolidPodClient
import dev.jeswr.solid.pod.StorageSource
import dev.jeswr.solid.pod.WebAccessControl
import dev.jeswr.solid.pod.discoverStorage
import dev.jeswr.solid.pod.probeStorageRoot
import dev.jeswr.solid.pod.profile
import dev.jeswr.solid.reactive.OkHttpClientAdapter
import dev.jeswr.solid.rdf.Graph
import dev.jeswr.solid.rdf.Term
import dev.jeswr.solid.rdf.Triple
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.URI

/**
 * Integration tests against a real in-memory Community Solid Server. Enabled
 * with `SOLID_KOTLIN_CSS_TESTS=1`; otherwise skipped so a plain `./gradlew test`
 * stays hermetic. Isolation comes from account-per-test (one CSS boot).
 */
@EnabledIfEnvironmentVariable(named = "SOLID_KOTLIN_CSS_TESTS", matches = "1")
class CSSIntegrationTest {
    private val ex = "https://example.org/ns#"

    @Test
    fun readWriteDeleteWithETags() {
        val alice = CSSHarness.createAccount()
        val pod = SolidPodClient(alice.client)
        val url = alice.podRoot.resolve("notes/note1.ttl")

        val graph = Graph.of(Triple(url.toString(), ex + "title", Term.string("First")))
        val created = pod.write(graph, url)
        assertTrue(created.status in 200..299)

        val resource = pod.readResource(url)
        assertTrue(resource.exists)
        assertNotNull(resource.etag)
        assertEquals(
            Term.string("First"),
            resource.graph.firstObject(Term.IRI(url.toString()), Term.IRI(ex + "title")),
        )

        val updated = resource.graph.insert(Triple(url.toString(), ex + "status", Term.string("updated")))
        pod.write(updated, url, ifMatch = resource.etag)

        assertThrows<PodException.PreconditionFailed> { pod.write(graph, url) }
        assertThrows<PodException.PreconditionFailed> { pod.write(updated, url, ifMatch = resource.etag) }

        pod.delete(url)
        assertFalse(pod.readResource(url).exists)
    }

    @Test
    fun containersAndListing() {
        val alice = CSSHarness.createAccount()
        val pod = SolidPodClient(alice.client)
        val container = alice.podRoot.resolve("things/")

        assertTrue(pod.ensureContainer(container))
        assertFalse(pod.ensureContainer(container))

        for (name in listOf("a.ttl", "b.ttl")) {
            val url = container.resolve(name)
            pod.write(Graph.of(Triple(url.toString(), ex + "name", Term.string(name))), url)
        }
        val listing = pod.listContainer(container)
        assertEquals(setOf("a.ttl", "b.ttl"), listing.members.map { it.path.substringAfterLast('/') }.toSet())
    }

    @Test
    fun storageDiscoveryAndProfile() {
        val alice = CSSHarness.createAccount()
        val pod = SolidPodClient(alice.client)

        val discovery = pod.discoverStorage(alice.webID)
        assertEquals(listOf(alice.podRoot), discovery.storages)
        assertEquals(StorageSource.PROFILE, discovery.source)
        assertEquals(alice.podRoot, pod.probeStorageRoot(alice.webID))

        val profile = pod.profile(alice.webID)
        assertTrue(profile.name?.startsWith("Test ") == true)
        assertEquals(listOf(alice.podRoot), profile.storages)
        assertEquals(1, profile.oidcIssuers.size)
    }

    @Test
    fun oidcDiscoveryAndDynamicRegistration() {
        val alice = CSSHarness.createAccount()
        val plain = OkHttpClientAdapter(OkHttpClient())

        val resolved = IssuerDiscovery.resolve(alice.webID, plain)
        assertEquals(alice.webID, resolved.webID)

        val provider = ProviderConfiguration.discover(resolved.issuer, plain)
        assertNotNull(provider.registrationEndpoint)

        val credentials = DynamicRegistration.register(
            provider = provider,
            redirectURIs = listOf(URI("solidkotlin://oauth/callback")),
            scopes = listOf("openid", "webid", "offline_access"),
            clientName = "solid-kotlin integration test",
            httpClient = plain,
        )
        assertTrue(credentials.clientID.isNotEmpty())
    }

    @Test
    fun dpopBoundRequestsAreAccepted() {
        val alice = CSSHarness.createAccount()
        val url = alice.podRoot.resolve("private/secret.ttl")
        val graph = Graph.of(Triple(url.toString(), ex + "level", Term.string("secret")))

        val anonymous = SolidPodClient(OkHttpClientAdapter(OkHttpClient()))
        val ex = assertThrows<PodException> { anonymous.write(graph, url) }
        assertTrue(ex is PodException.AuthenticationRequired || ex is PodException.Forbidden)

        SolidPodClient(alice.client).write(graph, url)
    }

    @Test
    fun wacGrantListRevoke() {
        val alice = CSSHarness.createAccount()
        val bob = CSSHarness.createAccount()
        val alicePod = SolidPodClient(alice.client)
        val bobPod = SolidPodClient(bob.client)
        val wac = WebAccessControl(alice.client)

        val url = alice.podRoot.resolve("shared/doc.ttl")
        alicePod.write(Graph.of(Triple(url.toString(), ex + "title", Term.string("Shared"))), url)

        assertThrows<PodException> { bobPod.readResource(url) }

        wac.grantRead(url, bob.webID)
        assertTrue(bobPod.readResource(url).exists)
        val grants = wac.grants(url)
        assertTrue(grants.any { it.agent == bob.webID && it.modes == listOf(AccessMode.READ) })
        assertTrue(grants.any { it.agent == alice.webID && AccessMode.CONTROL in it.modes })

        assertTrue(wac.revoke(bob.webID, url))
        assertThrows<PodException> { bobPod.readResource(url) }
    }

    @Test
    fun wacAuditViewListsOwnACLs() {
        val alice = CSSHarness.createAccount()
        val bob = CSSHarness.createAccount()
        val alicePod = SolidPodClient(alice.client)
        val wac = WebAccessControl(alice.client)

        val url = alice.podRoot.resolve("audit/item.ttl")
        alicePod.write(Graph.of(Triple(url.toString(), ex + "x", Term.string("y"))), url)
        wac.grantRead(url, bob.webID)

        val all = wac.allGrants(alice.podRoot)
        val entry = all.firstOrNull { it.resource == url }
        assertNotNull(entry)
        assertTrue(entry!!.grants.any { it.agent == bob.webID })
    }
}
