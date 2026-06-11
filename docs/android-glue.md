# Android glue

`solid-oidc` keeps every platform-specific seam behind an interface so the SDK
itself builds and unit-tests as pure Kotlin/JVM (no Android SDK, no emulator).
The two Android-coupled pieces — the redirect browser and the secure store —
live in **your app module** (which already pulls the Android SDK). These are the
reference implementations; copy them into your app.

Add to the app module:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("dev.jeswr.solid:solid-oidc:0.1.0")
    implementation("dev.jeswr.solid:solid-pod:0.1.0")
    implementation("dev.jeswr.solid:solid-reactive-auth:0.1.0")
    implementation("androidx.browser:browser:1.8.0")                 // Chrome Custom Tabs
    implementation("androidx.security:security-crypto:1.1.0-alpha06") // EncryptedSharedPreferences
}
```

## 1. Redirect flow — Chrome Custom Tabs

`AuthorizationUserAgent.authorize(authorizationURL, redirectURI)` must present
the provider's login page and return the full redirect URL. On Android: launch a
Custom Tab and resolve when the redirect-scheme `Activity` receives the callback
`Uri`.

```kotlin
import android.content.Context
import androidx.browser.customtabs.CustomTabsIntent
import dev.jeswr.solid.oidc.AuthorizationUserAgent
import dev.jeswr.solid.oidc.SolidOidcException
import java.net.URI
import java.util.concurrent.CompletableFuture

/**
 * Launches the authorization URL in a Chrome Custom Tab and completes when the
 * redirect Activity hands back the callback Uri via [onRedirect]. One-shot per
 * login; create a fresh instance (or reset the future) each time.
 */
class CustomTabsAuthorizationUserAgent(
    private val context: Context,
) : AuthorizationUserAgent {

    private val pending = CompletableFuture<URI>()

    override fun authorize(authorizationURL: URI, redirectURI: URI): URI {
        val intent = CustomTabsIntent.Builder().build()
        intent.intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.launchUrl(context, android.net.Uri.parse(authorizationURL.toString()))
        // Blocks the (background) auth thread until the redirect arrives.
        // Keep the login flow off the main thread.
        return try {
            pending.get()
        } catch (e: Exception) {
            throw (e.cause as? SolidOidcException) ?: SolidOidcException.LoginCancelled()
        }
    }

    /** Call from the redirect Activity's onCreate/onNewIntent with the callback Uri. */
    fun onRedirect(callback: android.net.Uri) {
        pending.complete(URI(callback.toString()))
    }

    /** Call if the Custom Tab is dismissed without completing. */
    fun onCancelled() {
        pending.completeExceptionally(SolidOidcException.LoginCancelled())
    }
}
```

Register the redirect scheme in the manifest and forward the callback:

```xml
<!-- AndroidManifest.xml -->
<activity android:name=".OAuthRedirectActivity"
          android:exported="true"
          android:launchMode="singleTask">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="myapp" android:host="oauth" />
    </intent-filter>
</activity>
```

```kotlin
class OAuthRedirectActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { AuthAgentHolder.agent?.onRedirect(it) }
        finish()
    }
}
```

(`AuthAgentHolder` is a small singleton holding the live
`CustomTabsAuthorizationUserAgent`; an `https` App Link works the same way.)

## 2. Secure persistence — EncryptedSharedPreferences

`SessionStore` persists the opaque session payload (tokens + DPoP private key).
Back it with Keystore-wrapped `EncryptedSharedPreferences` so nothing touches
disk in the clear:

```kotlin
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.jeswr.solid.oidc.SessionStore
import java.util.Base64

class EncryptedPrefsSessionStore(context: Context) : SessionStore {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "solid-kotlin.session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun save(key: String, data: ByteArray) {
        prefs.edit().putString(key, Base64.getEncoder().encodeToString(data)).apply()
    }

    override fun load(key: String): ByteArray? =
        prefs.getString(key, null)?.let { Base64.getDecoder().decode(it) }

    override fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }
}
```

For a hardware-backed DPoP key, generate the EC P-256 key in the Android Keystore
and adapt `DPoPKey` to sign through a `KeyStore`-resident `PrivateKey` instead of
the in-payload scalar (Keystore keys are non-exportable, so persist only a key
alias rather than `rawRepresentation`). The proof shape is identical.

## 3. Wiring it together

```kotlin
val agent = CustomTabsAuthorizationUserAgent(applicationContext)
AuthAgentHolder.agent = agent

val auth = SolidAuthClient(
    SolidAuthConfiguration(redirectURI = java.net.URI("myapp://oauth/callback")),
    httpClient = OkHttpClientAdapter(OkHttpClient()),
    userAgent = agent,
    sessionStore = EncryptedPrefsSessionStore(applicationContext),
)
// Run logIn off the main thread (the Custom Tab flow blocks).
```

The rest — pod IO, reactive auth interceptor, WAC — is platform-independent and
documented in the top-level README.
