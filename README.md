# solid-kotlin

Kotlin / Android SDKs for building [Solid](https://solidproject.org) apps. A
module-for-module Kotlin counterpart to [`solid-swift`](https://github.com/jeswr/solid-swift):
zero RDF dependencies, pure-JVM cores that unit-test without an emulator, and an
OkHttp-native "reactive auth" layer so app code never threads a token by hand.

| Module | What it gives you |
|---|---|
| `solid-rdf` | Minimal RDF core: Turtle + N-Triples parser/serialiser, immutable value-type `Graph` with pattern matching, RFC 3986 IRI resolution. Pure JVM. |
| `solid-object` | Typed domain models over RDF — a Kotlin port of [`@rdfjs/wrapper`](https://github.com/rdfjs/wrapper): subclass `TermWrapper`, expose properties through read/write accessors **or idiomatic Kotlin property delegates**, never assemble triples by hand. Ships `ProfileAgent` (WebID profiles) and `LDPContainer` (listings). Pure JVM. |
| `solid-oidc` | Solid-OIDC login: WebID → issuer discovery, authorization-code + PKCE, **DPoP (RFC 9449)** with ES256 on `java.security`, dynamic registration & client-id documents, token refresh, pluggable secure persistence. Pure JVM core; Android Custom Tabs + Keystore glue documented below. |
| `solid-reactive-auth` | The Kotlin analogue of [`@solid/reactive-authentication`](https://github.com/solid/reactive-authentication): an **OkHttp `Interceptor` + `Authenticator`** that transparently signs every request (`Authorization: DPoP …` + per-request proof), handles the `DPoP-Nonce` challenge, and refreshes/retries on `401`. |
| `solid-pod` | Pod resource IO: reads + conditional writes (ETag), typed 401/403/404/412 errors, containers, storage discovery, WebID profiles, full WAC access control. |

## Why these dependencies

| Dependency | Module | Why |
|---|---|---|
| **OkHttp** (`com.squareup.okhttp3:okhttp`) | `solid-reactive-auth` (api), integration tests | The de-facto Android/JVM HTTP client; its `Interceptor`/`Authenticator` SPI is exactly the seam the "reactive" pattern needs. The core modules depend only on a tiny `HttpClient` interface, so OkHttp is opt-in. |
| **OkHttp MockWebServer** | `solid-reactive-auth` tests | Drives the interceptor/authenticator against a real local HTTP server. |
| **JUnit 5** | all (test) | Test runner. |
| `androidx.browser` (Custom Tabs) | *app glue, documented* | The Solid-OIDC redirect flow on Android. Behind the `AuthorizationUserAgent` interface, so the OIDC flow unit-tests with a stub. |
| `androidx.security:security-crypto` | *app glue, documented* | EncryptedSharedPreferences / Android Keystore for token + DPoP-key persistence. Behind the `SessionStore` interface. |

RDF stays **dependency-free** — Turtle/N-Triples are hand-written in `solid-rdf`,
JSON is a tiny internal reader/writer, ES256/DPoP use `java.security` only. This
mirrors the Swift SDK's zero-third-party stance. (No JSON-LD in v1 — every Solid
server serves Turtle; byte-exact JSON documents go through
`SolidPodClient.writeData`.)

## Install

```kotlin
// settings.gradle.kts — until published, include the modules from a checkout
// or a composite build. Once published:
dependencies {
    implementation("dev.jeswr.solid:solid-pod:0.1.0")            // pulls solid-oidc + solid-object + solid-rdf
    implementation("dev.jeswr.solid:solid-reactive-auth:0.1.0")  // OkHttp interceptor (pulls solid-oidc)
    // or independently:
    implementation("dev.jeswr.solid:solid-object:0.1.0")
    implementation("dev.jeswr.solid:solid-rdf:0.1.0")
}
```

JDK 17, `minSdk 26`, `compileSdk 35`.

## Quick start

### Log in + reactive auth (solid-oidc + solid-reactive-auth)

App code uses a **plain `OkHttpClient`** — the interceptor signs everything:

```kotlin
import dev.jeswr.solid.oidc.*
import dev.jeswr.solid.reactive.*
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicReference

val sessionRef = AtomicReference<SolidSession?>()

// One client for the whole app — auth happens at the HTTP layer.
val client = installSolidReactiveAuth(OkHttpClient.Builder()) { sessionRef.get() }.build()

// Bootstrap calls (discovery, token exchange) use a plain transport.
val transport = OkHttpClientAdapter(OkHttpClient())

val auth = SolidAuthClient(
    SolidAuthConfiguration(redirectURI = java.net.URI("myapp://oauth/callback")),
    httpClient = transport,
    userAgent = customTabsAgent,        // Android Custom Tabs adapter — see below
    sessionStore = encryptedStore,      // EncryptedSharedPreferences — see below
)

val session = auth.restoreSession()
    ?: auth.logIn(java.net.URI("https://alice.example/profile/card#me"))
sessionRef.set(session)
println(session.webID)                  // the authenticated WebID

// From now on every request through `client` is DPoP-signed automatically:
client.newCall(okhttp3.Request.Builder().url(podResourceUrl).build()).execute()
```

- `logIn` accepts a WebID **or** an issuer URL. Several issuers on one WebID?
  Pass `chooseIssuer` — the SDK never silently takes the first.
- Tokens are DPoP-bound (per-request ES256 proof with `htm`/`htu`/`ath`,
  automatic `DPoP-Nonce` retry) and refresh automatically (the
  `SolidTokenAuthenticator` refreshes on `401` and replays once).
- Non-interactive: `SolidSession.clientCredentials(...)` for CSS client
  credentials / server-to-server agents.

### Read & write pod data (solid-pod)

`SolidPodClient` takes any `HttpClient`. Use `session.authenticatedClient` (or
an `OkHttpClientAdapter` over the reactive-auth client):

```kotlin
import dev.jeswr.solid.pod.*
import dev.jeswr.solid.rdf.*

val pod = SolidPodClient(session.authenticatedClient)

// Where is this user's storage? (pim:storage → type index → Link-header walk)
val storage = pod.discoverStorage(session.webID!!)
// storage.storages.size > 1 → ask the user; never silently take the first.

val noteURL = storage.storages[0].resolve("notes/today.ttl")
val note = pod.readResource(noteURL)                       // 404 → exists == false (read-for-create)
val updated = note.graph.insert(
    Triple(noteURL.toString(), "https://schema.org/text", Term.string("Hello from Kotlin")),
)
pod.write(updated, noteURL, ifMatch = if (note.exists) note.etag else null)  // 412 = lost update

pod.ensureContainer(storage.storages[0].resolve("notes/"))
val listing = pod.listContainer(storage.storages[0])

val profile = pod.profile(session.webID!!)
println("${profile.name} ${profile.storages} ${profile.oidcIssuers}")
```

Errors are typed — branch on subclasses, not strings:

```kotlin
try { pod.readResource(url) }
catch (e: PodException.AuthenticationRequired) { /* log in */ }
catch (e: PodException.Forbidden) { /* ask the owner for access */ }
catch (e: PodException.PreconditionFailed) { /* re-read, re-apply, retry */ }
```

### Share resources (WAC)

```kotlin
val wac = WebAccessControl(session.authenticatedClient)
wac.grantRead(noteURL, bobWebID)                                   // or grant(listOf(READ, WRITE), …)
val grants = wac.grants(noteURL)                                   // List<GrantEntry>
wac.revoke(bobWebID, noteURL)
val audit = wac.allGrants(storage.storages[0])                     // "what is shared with whom"
```

The ACL location always comes from the server's `Link rel="acl"` (never
guessed); granting on a resource without its own ACL first copies the inherited
`acl:default` rules so the owner is never locked out; ACP-governed resources
raise `PodException.AcpNotSupported`.

### Typed domain models with property delegates (solid-object)

`solid-object` is a Kotlin port of [`@rdfjs/wrapper`](https://github.com/rdfjs/wrapper):
define a class per domain concept and expose its fields as ordinary properties.
Two styles, both fully supported — **idiomatic Kotlin delegates** and the
explicit Swift-parity accessors:

```kotlin
import dev.jeswr.solid.obj.*
import dev.jeswr.solid.rdf.*

class Person(term: Term, graph: GraphBox) : TermWrapper(term, graph) {
    constructor(iri: String, graph: GraphBox) : this(Term.IRI(iri), graph)

    // delegate style — reads like an ordinary property
    var name: String? by optionalRW(FOAF.NAME, LiteralAs.string, LiteralFrom.string)
    val knows: WrappedSet<Person> by set(FOAF.KNOWS, TermAs.instance(::Person), TermFrom.instance())

    // explicit-accessor style (parity with the Swift SDK) — also works
    var age: Int?
        get() = OptionalFrom.subjectPredicate(this, EX + "age", LiteralAs.int)
        set(value) = OptionalAs.`object`(this, EX + "age", value, LiteralFrom.integerInt)
}

val box = GraphBox(Turtle.parse(turtle))     // the shared, mutable backing
val alice = Person("https://alice.example/#me", box)
alice.name = "Alice"                          // mutates box.graph in place
for (friend in alice.knows) println(friend.name ?: "?")

val turtleOut = Turtle.serialize(box.graph)   // ready for a conditional PUT
```

Two accessor families run in **opposite** directions, exactly as in
`@rdfjs/wrapper`: property cardinality reads with `…From`
(`RequiredFrom` / `OptionalFrom` / `SetFrom`) and writes with `…As`
(`RequiredAs` / `OptionalAs`); value mapping reads with `…As`
(`LiteralAs` / `IRIAs` / `TermAs`) and writes with `…From`
(`LiteralFrom` / `IRIFrom` / `TermFrom`). `OptionalFrom.firstSubjectPredicate`
(and the `optionalChain` delegate) encode fallback chains.
**`LiteralFrom.date` emits a correct `xsd:date` lexical (`YYYY-MM-DD`)** — the JS
`@rdfjs/wrapper` emits a `dateTime` lexical there, which SHACL rejects; this port
does not reproduce that bug (there's a test asserting the correct form).

Two ready-made wrappers ship:

```kotlin
val me = ProfileAgent(webID, box)             // foaf:name → schema:name → vcard:fn → as:name → rdfs:label
println(me.displayName)                       // never empty — falls back to the WebID
println(me.storageUrls.toList())              // pim:storage pod roots

val dir = LDPContainer("https://alice.example/notes/", box)
for (child in dir.contains) println("${child.name} ${child.isContainer}")
```

### Work with RDF directly (solid-rdf)

```kotlin
import dev.jeswr.solid.rdf.*

val graph = Turtle.parse(
    """
    @prefix foaf: <http://xmlns.com/foaf/0.1/>.
    <#me> a foaf:Person; foaf:name "Alice".
    """.trimIndent(),
    base = "https://alice.example/profile/card",
)
val names = graph.objects(
    Term.IRI("https://alice.example/profile/card#me"),
    Term.IRI("http://xmlns.com/foaf/0.1/name"),
)
val turtle = Turtle.serialize(graph, mapOf("foaf" to "http://xmlns.com/foaf/0.1/"))
val ntriples = NTriples.serialize(graph)
```

## Android glue

`solid-oidc`'s platform seams are interfaces, so the Android-specific pieces live
in your app (no Android SDK needed to build/test the SDK). Drop-in reference
implementations are in [`docs/android-glue.md`](docs/android-glue.md):

- **`CustomTabsAuthorizationUserAgent`** implements `AuthorizationUserAgent` by
  launching a **Chrome Custom Tab** (`androidx.browser`) and resolving when your
  redirect-scheme `Activity` receives the callback `Uri`.
- **`EncryptedPrefsSessionStore`** implements `SessionStore` over
  `androidx.security:security-crypto` EncryptedSharedPreferences (Keystore-wrapped),
  so tokens and the DPoP private key never touch disk unencrypted.

## Testing

```sh
./gradlew build test                              # hermetic unit tests (no network)
SOLID_KOTLIN_CSS_TESTS=1 ./gradlew :solid-pod:test  # + integration against a real local CSS
```

The integration suite boots an in-memory `@solid/community-server@8.0.0-alpha.3`
via `npx` on a random free port, provisions throwaway accounts through the CSS
account API (client-credentials DPoP tokens — the real authenticated path), and
exercises pod IO, discovery, OIDC discovery/registration, and WAC end to end.
Requires Node 18+. It is gated behind `SOLID_KOTLIN_CSS_TESTS=1` so plain
`./gradlew test` stays hermetic.

Every network / UI / storage seam is an interface (`HttpClient`,
`AuthorizationUserAgent`, `SessionStore`), so app code using these SDKs is
unit-testable without a server or emulator.

## Build notes

Gradle (wrapper) + Kotlin/JVM, JDK 17 toolchain (auto-provisioned). The
Android-coupled logic targets `minSdk 26`/`compileSdk 35` conceptually but is
built as pure Kotlin/JVM so the whole tree unit-tests on the JVM; the only
Android-SDK-dependent code is the documented glue, which compiles in the host
app.

## License

MIT.
