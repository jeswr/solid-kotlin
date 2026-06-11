package dev.jeswr.solid.oidc

import java.net.URI
import java.security.SecureRandom

/** Configuration for [SolidAuthClient]. */
public class SolidAuthConfiguration(
    /**
     * The OAuth redirect URI — normally a custom scheme (`myapp://oauth/callback`)
     * registered as an intent-filter, or an `https` App Link.
     */
    public val redirectURI: URI,
    /** How the app identifies itself. Default: dynamic registration. */
    public val client: ClientConfiguration = ClientConfiguration.DynamicRegistration(),
    /** Requested scopes. `openid` and `webid` are required; `offline_access` for refresh. */
    public val scopes: List<String> = listOf("openid", "webid", "offline_access"),
    /**
     * Called when a WebID advertises several OIDC issuers; return the one to log
     * in with. Without it, multiple issuers raise [SolidOidcException.AmbiguousIssuer].
     */
    public val chooseIssuer: ((List<URI>) -> URI)? = null,
    /** Key under which the session persists — override to keep several accounts. */
    public val sessionKey: String = "default",
)

/**
 * Solid-OIDC login orchestrator: WebID → issuer resolution, provider discovery,
 * client registration, authorization-code + PKCE via the platform redirect
 * agent, DPoP-bound token exchange, and secure session persistence.
 *
 * ```kotlin
 * val auth = SolidAuthClient(
 *     SolidAuthConfiguration(URI("myapp://oauth/callback")),
 *     httpClient = okHttpAdapter,
 *     userAgent = customTabsAgent,
 *     sessionStore = encryptedStore,
 * )
 * val session = auth.restoreSession()
 *     ?: auth.logIn(URI("https://alice.example/profile/card#me"))
 * ```
 */
public class SolidAuthClient(
    private val configuration: SolidAuthConfiguration,
    private val httpClient: HttpClient,
    private val userAgent: AuthorizationUserAgent,
    private val sessionStore: SessionStore = InMemorySessionStore(),
) {
    private val random = SecureRandom()

    /**
     * Run the full Solid-OIDC login flow. [webIDOrIssuer] is either the user's
     * WebID (its profile names the issuer) or an issuer URL.
     */
    public fun logIn(webIDOrIssuer: URI): SolidSession {
        val resolution = IssuerDiscovery.resolve(webIDOrIssuer, httpClient, configuration.chooseIssuer)
        val provider = ProviderConfiguration.discover(resolution.issuer, httpClient)
        val credentials = resolveClient(provider)

        val pkce = PKCE()
        val stateBytes = ByteArray(16).also { random.nextBytes(it) }
        val state = stateBytes.base64URL()

        val query = buildString {
            append("response_type=code")
            append("&client_id=").append(TokenClient.formEncode(credentials.clientID))
            append("&redirect_uri=").append(TokenClient.formEncode(configuration.redirectURI.toString()))
            append("&scope=").append(TokenClient.formEncode(configuration.scopes.joinToString(" ")))
            append("&state=").append(TokenClient.formEncode(state))
            append("&code_challenge=").append(TokenClient.formEncode(pkce.challenge))
            append("&code_challenge_method=S256")
            append("&prompt=consent")
        }
        val sep = if (provider.authorizationEndpoint.toString().contains("?")) "&" else "?"
        val authorizationURL = URI(provider.authorizationEndpoint.toString() + sep + query)

        val callback = userAgent.authorize(authorizationURL, configuration.redirectURI)
        val code = extractCode(callback, state)

        val dpopKey = DPoPKey()
        val tokens = TokenClient.request(
            tokenEndpoint = provider.tokenEndpoint,
            parameters = mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to configuration.redirectURI.toString(),
                "code_verifier" to pkce.verifier,
            ),
            clientCredentials = credentials,
            dpopKey = dpopKey,
            httpClient = httpClient,
        )

        val webID = webIDClaim(tokens) ?: resolution.webID
            ?: throw SolidOidcException.MissingWebIDClaim()

        val sessionState = SessionState(
            issuer = resolution.issuer,
            webID = webID,
            tokenEndpoint = provider.tokenEndpoint,
            clientCredentials = credentials,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAtEpochMillis = tokens.expiresIn?.let {
                System.currentTimeMillis() + (it * 1000).toLong()
            },
            dpopKey = dpopKey,
        )
        sessionStore.save(configuration.sessionKey, sessionState.toJson().toByteArray())
        return SolidSession(sessionState, httpClient, sessionStore, configuration.sessionKey)
    }

    /** Resume a previously persisted session, or null when none exists. */
    public fun restoreSession(): SolidSession? {
        val data = sessionStore.load(configuration.sessionKey) ?: return null
        val state = SessionState.fromJson(data.toString(Charsets.UTF_8))
        if (state == null) {
            sessionStore.delete(configuration.sessionKey)
            return null
        }
        return SolidSession(state, httpClient, sessionStore, configuration.sessionKey)
    }

    /** Forget the persisted session. */
    public fun logOut() {
        sessionStore.delete(configuration.sessionKey)
    }

    private fun resolveClient(provider: ProviderConfiguration): ClientCredentials =
        when (val c = configuration.client) {
            is ClientConfiguration.ClientIDDocument -> ClientCredentials(c.url.toString())
            is ClientConfiguration.PreRegistered -> ClientCredentials(c.clientID, c.clientSecret)
            is ClientConfiguration.DynamicRegistration -> DynamicRegistration.register(
                provider = provider,
                redirectURIs = listOf(configuration.redirectURI),
                scopes = configuration.scopes,
                clientName = c.clientName,
                httpClient = httpClient,
            )
        }

    public companion object {
        internal fun extractCode(callback: URI, expectedState: String): String {
            val items = parseQuery(callback)
            items["error"]?.let { error ->
                if (error == "access_denied") throw SolidOidcException.LoginCancelled()
                throw SolidOidcException.AuthorizationFailed(items["error_description"] ?: error)
            }
            if (items["state"] != expectedState) throw SolidOidcException.StateMismatch()
            return items["code"]
                ?: throw SolidOidcException.AuthorizationFailed("the callback carried no code")
        }

        private fun parseQuery(uri: URI): Map<String, String> {
            val query = uri.rawQuery ?: return emptyMap()
            return query.split("&").mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq < 0) return@mapNotNull null
                val k = java.net.URLDecoder.decode(part.substring(0, eq), Charsets.UTF_8)
                val v = java.net.URLDecoder.decode(part.substring(eq + 1), Charsets.UTF_8)
                k to v
            }.toMap()
        }

        /**
         * The `webid` claim, preferring the access token (Solid-OIDC §5),
         * falling back to the ID token, then to `sub` when it is a URL.
         */
        internal fun webIDClaim(tokens: TokenResponse): URI? {
            for (candidate in listOfNotNull(tokens.accessToken, tokens.idToken)) {
                val claims = JWTClaims.decode(candidate) ?: continue
                val webid = claims.webid
                if (webid != null) {
                    runCatching { URI(webid) }.getOrNull()?.let { return it }
                }
                val sub = claims.sub
                if (sub != null && sub.contains("://")) {
                    runCatching { URI(sub) }.getOrNull()?.let { return it }
                }
            }
            return null
        }
    }
}
