package dev.jeswr.solid.oidc

import java.net.URI

/** How this app identifies itself to the OIDC provider. */
public sealed interface ClientConfiguration {
    /**
     * Solid-OIDC Client Identifier Document mode: the `client_id` IS the URL of
     * a dereferenceable JSON-LD document describing the app (stable, named
     * consent screen). The document must list the app's redirect URI.
     */
    public data class ClientIDDocument(public val url: URI) : ClientConfiguration

    /**
     * OAuth 2.0 dynamic client registration (RFC 7591) at login time — the
     * zero-setup default; the registered id is remembered with the session.
     */
    public data class DynamicRegistration(public val clientName: String? = null) : ClientConfiguration

    /** Credentials pre-registered with the issuer out of band. */
    public data class PreRegistered(
        public val clientID: String,
        public val clientSecret: String? = null,
    ) : ClientConfiguration
}

/** Registered client credentials. */
public data class ClientCredentials(
    public val clientID: String,
    public val clientSecret: String? = null,
)

/**
 * Dynamic client registration (RFC 7591) against the provider's
 * `registration_endpoint`.
 */
public object DynamicRegistration {
    public fun register(
        provider: ProviderConfiguration,
        redirectURIs: List<URI>,
        scopes: List<String>,
        clientName: String? = null,
        httpClient: HttpClient,
    ): ClientCredentials {
        val endpoint = provider.registrationEndpoint
            ?: throw SolidOidcException.RegistrationFailed(
                provider.issuer,
                null,
                "the issuer does not support dynamic registration; use a Client Identifier " +
                    "Document or pre-registered credentials",
            )
        val metadata = LinkedHashMap<String, Any?>()
        metadata["redirect_uris"] = redirectURIs.map { it.toString() }
        metadata["grant_types"] = listOf("authorization_code", "refresh_token")
        metadata["response_types"] = listOf("code")
        metadata["token_endpoint_auth_method"] = "none"
        metadata["application_type"] = "native"
        metadata["scope"] = scopes.joinToString(" ")
        if (clientName != null) metadata["client_name"] = clientName

        val response = httpClient.send(
            HttpRequest(
                method = "POST",
                url = endpoint.toString(),
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                body = Json.write(metadata).toByteArray(),
            ),
        )
        if (!response.isSuccess) {
            throw SolidOidcException.RegistrationFailed(provider.issuer, response.statusCode, response.bodyText)
        }
        val map = runCatching { Json.parseObject(response.bodyText) }.getOrElse {
            throw SolidOidcException.RegistrationFailed(
                provider.issuer,
                response.statusCode,
                "malformed registration response: $it",
            )
        }
        val clientID = map["client_id"] as? String
            ?: throw SolidOidcException.RegistrationFailed(
                provider.issuer,
                response.statusCode,
                "registration response missing client_id",
            )
        return ClientCredentials(clientID, map["client_secret"] as? String)
    }
}

/**
 * A Solid-OIDC Client Identifier Document. Apps with a web presence host this
 * JSON-LD at a stable URL and use that URL as their `client_id`; the provider
 * fetches it during login and shows `client_name` on the consent screen.
 */
public class ClientIDDocument(
    /** MUST equal the URL the document is served from, byte-for-byte. */
    public val clientID: URI,
    public val clientName: String,
    public val redirectURIs: List<URI>,
    /** Must include `openid` and `webid`. */
    public val scope: String = "openid webid offline_access",
    public val grantTypes: List<String> = listOf("authorization_code", "refresh_token"),
    public val clientURI: URI? = null,
    public val logoURI: URI? = null,
) {
    init {
        require(redirectURIs.isNotEmpty()) { "redirectURIs must list at least one callback URL" }
        val parts = scope.split(" ")
        require("openid" in parts && "webid" in parts) { "scope must include openid and webid" }
    }

    public companion object {
        public const val CONTEXT: String = "https://www.w3.org/ns/solid/oidc-context.jsonld"
    }

    /** The document as `application/ld+json` bytes, for serving. */
    public fun serialized(): ByteArray {
        val map = LinkedHashMap<String, Any?>()
        map["@context"] = listOf(CONTEXT)
        map["client_id"] = clientID.toString()
        map["client_name"] = clientName
        map["redirect_uris"] = redirectURIs.map { it.toString() }
        map["scope"] = scope
        map["grant_types"] = grantTypes
        map["response_types"] = listOf("code")
        map["token_endpoint_auth_method"] = "none"
        if (clientURI != null) map["client_uri"] = clientURI.toString()
        if (logoURI != null) map["logo_uri"] = logoURI.toString()
        return Json.write(map).toByteArray()
    }
}
