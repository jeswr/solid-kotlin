package dev.jeswr.solid.oidc

import java.net.URI

/**
 * The subset of the OpenID provider metadata
 * (`/.well-known/openid-configuration`) this client uses.
 */
public class ProviderConfiguration(
    public val issuer: URI,
    public val authorizationEndpoint: URI,
    public val tokenEndpoint: URI,
    public val registrationEndpoint: URI?,
    public val jwksUri: URI?,
    /** Solid-OIDC issuers advertise `webid` here. */
    public val scopesSupported: List<String>?,
) {
    public companion object {
        /** Fetch and decode the provider configuration for [issuer]. */
        @Suppress("UNCHECKED_CAST")
        public fun discover(issuer: URI, httpClient: HttpClient): ProviderConfiguration {
            var base = issuer.toString()
            if (!base.endsWith("/")) base += "/"
            val url = base + ".well-known/openid-configuration"
            val response = httpClient.send(HttpRequest(url = url, headers = mapOf("Accept" to "application/json")))
            if (!response.isSuccess) {
                throw SolidOidcException.ProviderConfigurationInvalid(
                    issuer,
                    "discovery returned HTTP ${response.statusCode} — is this an OIDC issuer?",
                )
            }
            val map = runCatching { Json.parseObject(response.bodyText) }.getOrElse {
                throw SolidOidcException.ProviderConfigurationInvalid(issuer, "malformed metadata: $it")
            }
            fun req(key: String): URI = (map[key] as? String)?.let { URI(it) }
                ?: throw SolidOidcException.ProviderConfigurationInvalid(issuer, "missing $key")
            return ProviderConfiguration(
                issuer = (map["issuer"] as? String)?.let { URI(it) } ?: issuer,
                authorizationEndpoint = req("authorization_endpoint"),
                tokenEndpoint = req("token_endpoint"),
                registrationEndpoint = (map["registration_endpoint"] as? String)?.let { URI(it) },
                jwksUri = (map["jwks_uri"] as? String)?.let { URI(it) },
                scopesSupported = (map["scopes_supported"] as? List<Any?>)?.mapNotNull { it as? String },
            )
        }
    }
}
