package dev.jeswr.solid.integration

import dev.jeswr.solid.oidc.ClientCredentials
import dev.jeswr.solid.oidc.HttpClient
import dev.jeswr.solid.oidc.HttpRequest
import dev.jeswr.solid.oidc.HttpResponse
import dev.jeswr.solid.oidc.SolidSession
import dev.jeswr.solid.rdf.Graph
import dev.jeswr.solid.rdf.Term
import dev.jeswr.solid.rdf.Triple
import dev.jeswr.solid.rdf.Turtle
import dev.jeswr.solid.reactive.OkHttpClientAdapter
import okhttp3.OkHttpClient
import java.net.ServerSocket
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Whether the optional CSS integration suite is enabled. */
internal object CSSGate {
    val enabled: Boolean get() = System.getenv("SOLID_KOTLIN_CSS_TESTS") == "1"
}

internal class TestAccount(
    val webID: URI,
    val podRoot: URI,
    val email: String,
    val password: String,
    /** An HttpClient authenticated as this account (client-credentials DPoP). */
    val client: HttpClient,
)

internal class HarnessException(message: String) : Exception(message)

/**
 * In-memory Community Solid Server test harness, mirroring solid-swift's
 * CSSHarness: boots `npx -y @solid/community-server@7` (default config: memory +
 * WAC) on a random free port and provisions isolated accounts
 * through the CSS account API with client-credentials DPoP tokens. Gated behind
 * `SOLID_KOTLIN_CSS_TESTS=1`.
 */
internal object CSSHarness {
    private var baseURL: URI? = null
    private var process: Process? = null

    /** A plain (cookie-less) transport for account provisioning. */
    private val http: HttpClient = OkHttpClientAdapter(
        OkHttpClient.Builder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build(),
    )

    @Synchronized
    fun start(): URI {
        baseURL?.let { return it }

        val port = freePort()
        val base = URI("http://localhost:$port/")
        val proc = ProcessBuilder(
            "npx", "-y", "@solid/community-server@7",
            "-p", port.toString(), "-b", base.toString(), "-l", "error",
        ).redirectErrorStream(false).start()
        process = proc
        Runtime.getRuntime().addShutdownHook(Thread { proc.destroy() })

        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) throw HarnessException("CSS exited during boot (exit ${proc.exitValue()})")
            val ok = runCatching { http.send(HttpRequest(url = base.toString())).statusCode < 500 }.getOrDefault(false)
            if (ok) {
                baseURL = base
                return base
            }
            Thread.sleep(500)
        }
        throw HarnessException("CSS did not become ready on port $port within 180s")
    }

    fun createAccount(): TestAccount {
        val base = start()
        val pod = "test-" + UUID.randomUUID().toString().take(13).lowercase()
        val email = "$pod@example.com"
        val password = "$pod-pass-123"
        val webID = URI("${base}$pod/profile/card#me")
        val podRoot = URI("${base}$pod/")

        val cookie = arrayOf<String?>(null)
        jsonPost(URI("${base}.account/account/"), emptyMap(), cookie)
        val indexReq = HttpRequest(url = "${base}.account/").let {
            if (cookie[0] != null) it.withHeader("Cookie", cookie[0]!!) else it
        }
        val index = http.send(indexReq)
        @Suppress("UNCHECKED_CAST")
        val controls = parseJson(index.bodyText)["controls"] as? Map<String, Any?>
            ?: throw HarnessException("unexpected CSS account index: ${index.bodyText}")
        @Suppress("UNCHECKED_CAST")
        val passwordControls = controls["password"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val accountControls = controls["account"] as? Map<String, Any?>
        val createPassword = URI(passwordControls?.get("create") as String)
        val createPod = URI(accountControls?.get("pod") as String)
        val createCredentials = URI(accountControls["clientCredentials"] as String)

        jsonPost(createPassword, mapOf("email" to email, "password" to password), cookie)
        jsonPost(createPod, mapOf("name" to pod), cookie)
        val credentials = jsonPost(
            createCredentials,
            mapOf("name" to "solid-kotlin-harness", "webId" to webID.toString()),
            cookie,
        )
        val id = credentials["id"] as? String ?: throw HarnessException("missing credentials id")
        val secret = credentials["secret"] as? String ?: throw HarnessException("missing credentials secret")

        val tokenEndpoint = URI("${base}.oidc/token")
        val session = SolidSession.clientCredentials(
            tokenEndpoint = tokenEndpoint,
            issuer = base,
            credentials = ClientCredentials(id, secret),
            httpClient = http,
            webID = webID,
        )

        // Seed the bare profile: keep solid:oidcIssuer, add foaf:name + pim:storage.
        val foaf = "http://xmlns.com/foaf/0.1/"
        val pim = "http://www.w3.org/ns/pim/space#"
        val solid = "http://www.w3.org/ns/solid/terms#"
        val profileDoc = URI("${base}$pod/profile/card")
        val me = Term.IRI(webID.toString())
        val doc = Term.IRI(profileDoc.toString())
        val profile = Graph.of(
            Triple(doc, Term.IRI(rdfType), Term.IRI(foaf + "PersonalProfileDocument")),
            Triple(doc, Term.IRI(foaf + "maker"), me),
            Triple(doc, Term.IRI(foaf + "primaryTopic"), me),
            Triple(me, Term.IRI(rdfType), Term.IRI(foaf + "Person")),
            Triple(me, Term.IRI(solid + "oidcIssuer"), Term.IRI(base.toString())),
            Triple(me, Term.IRI(pim + "storage"), Term.IRI(podRoot.toString())),
            Triple(me, Term.IRI(foaf + "name"), Term.string("Test $pod")),
        )
        val seedBody = Turtle.serialize(profile, mapOf("foaf" to foaf, "pim" to pim, "solid" to solid))
        val seed = HttpRequest("PUT", profileDoc.toString(), mapOf("Content-Type" to "text/turtle"), seedBody.toByteArray())
        val seeded = session.send(seed)
        if (!seeded.isSuccess) throw HarnessException("profile seed failed: HTTP ${seeded.statusCode} ${seeded.bodyText}")

        return TestAccount(webID, podRoot, email, password, session.authenticatedClient)
    }

    private val rdfType = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"

    private fun jsonPost(url: URI, body: Map<String, Any?>, cookie: Array<String?>): Map<String, Any?> {
        var request = HttpRequest("POST", url.toString(), body = writeJson(body).toByteArray())
            .withHeader("Content-Type", "application/json")
        cookie[0]?.let { request = request.withHeader("Cookie", it) }
        val response = http.send(request)
        response.header("Set-Cookie")?.split(";")?.firstOrNull()?.let { cookie[0] = it }
        if (!response.isSuccess) {
            throw HarnessException("CSS account API $url -> ${response.statusCode}: ${response.bodyText}")
        }
        return parseJson(response.bodyText)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    // Tiny JSON helpers (the module's solid-oidc Json is internal).
    private fun writeJson(map: Map<String, Any?>): String =
        "{" + map.entries.joinToString(",") { (k, v) ->
            "\"$k\":" + when (v) {
                null -> "null"
                is String -> "\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
                else -> v.toString()
            }
        } + "}"

    @Suppress("UNCHECKED_CAST")
    private fun parseJson(text: String): Map<String, Any?> =
        dev.jeswr.solid.integration.MiniJson.parse(text) as? Map<String, Any?> ?: emptyMap()
}
