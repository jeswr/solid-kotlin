package dev.jeswr.solid.oidc

import java.net.URI

/**
 * The persisted shape of a session — everything needed to resume authenticated
 * requests after relaunch.
 */
public class SessionState(
    public var issuer: URI,
    public var webID: URI?,
    public var tokenEndpoint: URI,
    public var clientCredentials: ClientCredentials,
    public var accessToken: String,
    public var refreshToken: String?,
    public var expiresAtEpochMillis: Long?,
    public var dpopKey: DPoPKey,
) {
    internal fun toJson(): String {
        val map = LinkedHashMap<String, Any?>()
        map["issuer"] = issuer.toString()
        map["webID"] = webID?.toString()
        map["tokenEndpoint"] = tokenEndpoint.toString()
        map["clientID"] = clientCredentials.clientID
        map["clientSecret"] = clientCredentials.clientSecret
        map["accessToken"] = accessToken
        map["refreshToken"] = refreshToken
        map["expiresAt"] = expiresAtEpochMillis
        map["dpopKey"] = dpopKey.rawRepresentation.base64URL()
        return Json.write(map)
    }

    internal companion object {
        fun fromJson(json: String): SessionState? = runCatching {
            val map = Json.parseObject(json)
            SessionState(
                issuer = URI(map["issuer"] as String),
                webID = (map["webID"] as? String)?.let { URI(it) },
                tokenEndpoint = URI(map["tokenEndpoint"] as String),
                clientCredentials = ClientCredentials(
                    map["clientID"] as String,
                    map["clientSecret"] as? String,
                ),
                accessToken = map["accessToken"] as String,
                refreshToken = map["refreshToken"] as? String,
                expiresAtEpochMillis = (map["expiresAt"] as? Number)?.toLong(),
                dpopKey = DPoPKey.fromRaw(Base64URL.decode(map["dpopKey"] as String)),
            )
        }.getOrNull()
    }
}

/**
 * An authenticated Solid session: holds the DPoP-bound tokens, refreshes them
 * when they expire, and signs outgoing requests (`Authorization: DPoP …` +
 * per-request proof with `ath`), including the RFC 9449 `DPoP-Nonce` retry.
 *
 * Get one from [SolidAuthClient.logIn] or [SolidAuthClient.restoreSession]. For
 * resource IO, pass [authenticatedClient] to `SolidPodClient`; in
 * solid-reactive-auth this same logic is wrapped as an OkHttp interceptor so app
 * code never threads a token by hand.
 *
 * Thread-safe: state mutation is synchronised on this instance.
 */
public class SolidSession internal constructor(
    private var state: SessionState,
    private val httpClient: HttpClient,
    private val store: SessionStore? = null,
    private val storeKey: String? = null,
) {
    /** The WebID this session is authenticated as, if known. */
    public val webID: URI? = state.webID

    /** The OIDC issuer that minted the tokens. */
    public val issuer: URI = state.issuer

    private val lock = Any()

    /** Most recent server-supplied DPoP nonce per host, replayed proactively. */
    private val nonces = HashMap<String, String>()

    /** An [HttpClient] that signs every request with this session's credentials. */
    public val authenticatedClient: HttpClient
        get() = HttpClient { request -> send(request) }

    /** A valid access token, refreshing first when it is (about to be) expired. */
    public fun currentAccessToken(): String = synchronized(lock) {
        val expiresAt = state.expiresAtEpochMillis
        if (expiresAt != null && expiresAt - System.currentTimeMillis() < 30_000) {
            refreshTokensLocked()
        }
        state.accessToken
    }

    /**
     * Send [request] with `Authorization: DPoP <token>` and a fresh DPoP proof.
     * Handles `use_dpop_nonce` challenges and retries once after a token refresh
     * when the server rejects the token as expired.
     */
    public fun send(request: HttpRequest): HttpResponse {
        var response = sendOnce(request)
        val host = hostOf(request.url)
        if (response.statusCode == 401 &&
            response.header("DPoP-Nonce") != null &&
            response.header("WWW-Authenticate")?.contains("use_dpop_nonce") == true
        ) {
            synchronized(lock) { nonces[host] = response.header("DPoP-Nonce")!! }
            response = sendOnce(request)
        }
        if (response.statusCode == 401 && state.refreshToken != null) {
            synchronized(lock) { refreshTokensLocked() }
            response = sendOnce(request)
        }
        return response
    }

    private fun sendOnce(request: HttpRequest): HttpResponse {
        val token = currentAccessToken()
        val host = hostOf(request.url)
        val nonce = synchronized(lock) { nonces[host] }
        val signed = request
            .withHeader("Authorization", "DPoP $token")
            .withHeader(
                "DPoP",
                state.dpopKey.proof(
                    method = request.method,
                    url = request.url,
                    accessToken = token,
                    nonce = nonce,
                ),
            )
        return httpClient.send(signed)
    }

    /**
     * Exchange the refresh token for new tokens (DPoP-bound to this session's
     * key). Throws [SolidOidcException.SessionExpired] when no refresh token
     * exists.
     */
    public fun refreshTokens(): Unit = synchronized(lock) { refreshTokensLocked() }

    private fun refreshTokensLocked() {
        val refreshToken = state.refreshToken ?: throw SolidOidcException.SessionExpired()
        val response = TokenClient.request(
            tokenEndpoint = state.tokenEndpoint,
            parameters = mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken),
            clientCredentials = state.clientCredentials,
            dpopKey = state.dpopKey,
            httpClient = httpClient,
        )
        state.accessToken = response.accessToken
        response.refreshToken?.let { state.refreshToken = it }
        state.expiresAtEpochMillis = response.expiresIn?.let {
            System.currentTimeMillis() + (it * 1000).toLong()
        }
        persist()
    }

    /** Remove the persisted session. */
    public fun logout() {
        val store = store ?: return
        val key = storeKey ?: return
        store.delete(key)
    }

    private fun persist() {
        val store = store ?: return
        val key = storeKey ?: return
        store.save(key, state.toJson().toByteArray())
    }

    private fun hostOf(url: String): String = runCatching { URI(url).host ?: "" }.getOrDefault("")

    public companion object {
        /**
         * Open a session with the OAuth `client_credentials` grant, DPoP-bound
         * to a (fresh) proof key — the non-interactive path for credentials
         * minted out of band (e.g. CSS client credentials, server-to-server
         * agents). The session is memory-only and cannot refresh.
         */
        public fun clientCredentials(
            tokenEndpoint: URI,
            issuer: URI,
            credentials: ClientCredentials,
            httpClient: HttpClient,
            webID: URI? = null,
            dpopKey: DPoPKey = DPoPKey(),
            scopes: List<String> = listOf("webid"),
        ): SolidSession {
            val tokens = TokenClient.request(
                tokenEndpoint = tokenEndpoint,
                parameters = mapOf(
                    "grant_type" to "client_credentials",
                    "scope" to scopes.joinToString(" "),
                ),
                clientCredentials = credentials,
                dpopKey = dpopKey,
                httpClient = httpClient,
            )
            val state = SessionState(
                issuer = issuer,
                webID = SolidAuthClient.webIDClaim(tokens) ?: webID,
                tokenEndpoint = tokenEndpoint,
                clientCredentials = credentials,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresAtEpochMillis = tokens.expiresIn?.let {
                    System.currentTimeMillis() + (it * 1000).toLong()
                },
                dpopKey = dpopKey,
            )
            return SolidSession(state, httpClient)
        }
    }
}
