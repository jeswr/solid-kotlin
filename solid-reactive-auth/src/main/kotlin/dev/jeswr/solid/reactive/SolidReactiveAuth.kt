package dev.jeswr.solid.reactive

import dev.jeswr.solid.oidc.SolidSession
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Supplies the current authenticated [SolidSession] to the reactive auth layer.
 *
 * App code logs in once (via `SolidAuthClient`) and hands the resulting session
 * here; the interceptor then signs every request transparently. When no session
 * is available yet, requests go out unauthenticated (so public resources still
 * work) and a `401` simply surfaces — the analogue of the browser library's
 * "patch global fetch, upgrade on 401".
 */
public fun interface SolidSessionProvider {
    public fun currentSession(): SolidSession?
}

/**
 * The Kotlin analogue of `@solid/reactive-authentication`: install this on a
 * plain [OkHttpClient] and app code never threads a token by hand.
 *
 * - [DPoPSigningInterceptor] attaches `Authorization: DPoP <token>` + a fresh
 *   per-request DPoP proof (`htm`/`htu`/`ath`) when a session exists, and
 *   transparently retries once on an RFC 9449 `DPoP-Nonce` challenge with the
 *   server-supplied nonce.
 * - [SolidTokenAuthenticator] (an OkHttp [Authenticator]) reacts to a `401` by
 *   refreshing the session's tokens and replaying the request once.
 *
 * ```kotlin
 * val sessionRef = AtomicReference<SolidSession?>()
 * val client = OkHttpClient.Builder()
 *     .let { installSolidReactiveAuth(it) { sessionRef.get() } }
 *     .build()
 * // … after SolidAuthClient.logIn(...): sessionRef.set(session)
 * client.newCall(Request.Builder().url(podResource).build()).execute()  // signed automatically
 * ```
 */
public class DPoPSigningInterceptor(private val sessions: SolidSessionProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val session = sessions.currentSession()
            ?: return chain.proceed(chain.request())

        val signed = sign(chain.request(), session)
        var response = chain.proceed(signed)

        // RFC 9449 §9 DPoP-Nonce challenge: cache the nonce, replay once.
        if (response.code == 401 &&
            response.header("DPoP-Nonce") != null &&
            response.header("WWW-Authenticate")?.contains("use_dpop_nonce") == true
        ) {
            session.recordNonce(signed.url.toString(), response.header("DPoP-Nonce")!!)
            response.close()
            response = chain.proceed(sign(chain.request(), session))
        }
        return response
    }

    private fun sign(request: Request, session: SolidSession): Request {
        val token = session.currentAccessToken()
        val proof = session.dpopProof(request.method, request.url.toString(), token)
        return request.newBuilder()
            .header("Authorization", "DPoP $token")
            .header("DPoP", proof)
            .build()
    }
}

/**
 * OkHttp [Authenticator]: on a `401` that survived the interceptor's nonce
 * dance, refresh the session's tokens and replay the request once. Returning
 * null gives up (surfacing the 401 to the caller), so a genuinely
 * unauthorised request is not looped.
 */
public class SolidTokenAuthenticator(private val sessions: SolidSessionProvider) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null // already retried — give up
        val session = sessions.currentSession() ?: return null
        if (!session.canRefresh) return null

        return runCatching {
            session.refreshTokens()
            val token = session.currentAccessToken()
            val proof = session.dpopProof(response.request.method, response.request.url.toString(), token)
            response.request.newBuilder()
                .header("Authorization", "DPoP $token")
                .header("DPoP", proof)
                .build()
        }.getOrNull()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}

/**
 * Install both the DPoP signing interceptor and the token-refresh authenticator
 * on an OkHttp builder — the one-liner app developers call.
 */
public fun installSolidReactiveAuth(
    builder: OkHttpClient.Builder,
    sessions: SolidSessionProvider,
): OkHttpClient.Builder =
    builder
        .addInterceptor(DPoPSigningInterceptor(sessions))
        .authenticator(SolidTokenAuthenticator(sessions))
