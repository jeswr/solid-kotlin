package dev.jeswr.solid.oidc

import java.net.URI

/**
 * Typed failures raised by the auth layer. Callers branch on subclasses — never
 * on message strings.
 */
public sealed class SolidOidcException(message: String) : Exception(message) {
    /** The login input could not be resolved to an OIDC issuer. */
    public class IssuerResolutionFailed(public val input: URI, public val reason: String) :
        SolidOidcException("Could not resolve an OIDC issuer from $input: $reason")

    /** The WebID profile was dereferenced but advertises no `solid:oidcIssuer`. */
    public class NoOidcIssuer(public val webID: URI) :
        SolidOidcException(
            "The WebID profile $webID has no solid:oidcIssuer. The pod owner should add " +
                "one, or log in with the issuer URL directly.",
        )

    /**
     * The WebID advertises several issuers and no `chooseIssuer` callback was
     * provided — never silently take the first.
     */
    public class AmbiguousIssuer(public val webID: URI, public val issuers: List<URI>) :
        SolidOidcException(
            "$webID advertises ${issuers.size} OIDC issuers. Provide chooseIssuer so the " +
                "user can pick one.",
        )

    /** `.well-known/openid-configuration` was missing or malformed. */
    public class ProviderConfigurationInvalid(public val issuer: URI, public val reason: String) :
        SolidOidcException("Invalid OpenID provider configuration at $issuer: $reason")

    /** Dynamic client registration was rejected (or no registration endpoint). */
    public class RegistrationFailed(
        public val issuer: URI,
        public val status: Int?,
        public val detail: String,
    ) : SolidOidcException(
        "Dynamic client registration with $issuer failed" +
            (status?.let { " (HTTP $it)" } ?: "") + ": $detail",
    )

    /** The authorization redirect was malformed or reported an OAuth error. */
    public class AuthorizationFailed(public val reason: String) :
        SolidOidcException("Authorization failed: $reason")

    /** The user dismissed the login flow. Not a failure — show login again. */
    public class LoginCancelled : SolidOidcException("Login was cancelled before completing.")

    /** The `state` parameter on the callback did not match the request. */
    public class StateMismatch :
        SolidOidcException("The authorization callback's state did not match the request.")

    /** The token endpoint rejected the request. */
    public class TokenRequestFailed(
        public val status: Int,
        public val error: String?,
        public val detail: String,
    ) : SolidOidcException(
        "Token request failed (HTTP $status" + (error?.let { ", $it" } ?: "") + "): $detail",
    )

    /** Neither the access token nor the ID token carried a `webid` claim. */
    public class MissingWebIDClaim :
        SolidOidcException(
            "The issuer returned tokens without a webid claim. Make sure the requested scope " +
                "includes \"webid\" and the issuer supports Solid-OIDC.",
        )

    /** No refresh token is available and the access token has expired. */
    public class SessionExpired :
        SolidOidcException("The session has expired and cannot be refreshed. Log in again.")

    /** A secure-storage operation failed. */
    public class StorageFailure(public val detail: String) :
        SolidOidcException("Secure session storage failed: $detail")
}
