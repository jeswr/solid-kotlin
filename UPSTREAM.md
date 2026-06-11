# Upstream contribution plan

This repo is the staging ground; the goal is first-class Kotlin/Android support
in the wider Solid ecosystem. **No upstream issues/PRs/transfers happen from
here without explicit lead approval** — this file is the proposal.

## Where each piece could land

| Piece | Proposed destination | Rationale |
|---|---|---|
| Whole package (`solid-kotlin`) | **`solid-contrib/solid-kotlin`** (transfer or fork-and-bless) | solid-contrib hosts community client libraries; no maintained Kotlin/Android Solid client exists today. Keep the module split so consumers take only what they need. |
| `solid-rdf` | Could stand alone | Useful beyond Solid (any JVM RDF consumer that wants a tiny Turtle core). Standalone only on demand. |
| `solid-oidc` + `solid-reactive-auth` | With the package; **listed on solidproject.org developer-tools** next to the JS/Swift auth libraries | Fills the documented Android gap. The OkHttp reactive-auth pattern is the Android-idiomatic analogue of the browser library's global-fetch patch. |
| DPoP (`DPoP.kt`) | Possibly a tiny `kotlin-dpop` | RFC 9449 is useful far beyond Solid (any OAuth API); pure-JCA, no third-party crypto. Clean standalone candidate under jeswr/ first. |
| CSS test harness (`CSSHarness.kt`) | A `solid-kotlin` testing artifact, or `solid-contrib/test-suite` | Same shape as the JS/Swift "boot CSS + provision accounts" pattern; other Kotlin apps will want it. Promote from test code to a published `solid-testing` artifact before upstreaming. |

## Known gaps to close before (or while) upstreaming

- **Android instrumented tests**: the Custom Tabs / EncryptedSharedPreferences
  glue is documented + interface-tested but not run on an emulator in CI. Add a
  `connectedCheck` job and convert the glue docs into a real `com.android.library`
  module once an Android SDK is available in CI.
- **JSON-LD**: not parsed (documented decision, mirrors solid-swift). A v2 optional
  artifact; clients only *serve* client-id documents, never parse them.
- **ACP**: read/author not supported; WAC only (Inrupt ESS uses ACP).
- **Notifications**: no WebSocketChannel2023 subscription support yet.
- **Hardware-backed DPoP**: the key is a software scalar persisted via the
  SessionStore; an Android Keystore-resident (non-exportable) P-256 option would
  be a differentiator — adapt `DPoPKey` to sign through a `KeyStore` alias.
- **Conformance**: no run against `solid-contrib/conformance-test-harness` yet.
- **Multiplatform**: the pure modules (`solid-rdf`, `solid-object`) are plausible
  Kotlin Multiplatform targets; currently JVM-only.

## Relationship to solid-swift

This package is a deliberate module-for-module port of
[`solid-swift`](https://github.com/jeswr/solid-swift): same product split, same
public API shape (accessor suffix conventions, typed errors, WAC grant model,
storage-discovery strategy order), same behavioural reference (the in-house
TypeScript `solid-kit`). Bug fixes and API changes should be kept in sync across
both where it makes sense. The one intentional addition is `solid-reactive-auth`
(no Swift equivalent — Swift patches nothing; the OkHttp interceptor is the
Kotlin-idiomatic "reactive" layer).
