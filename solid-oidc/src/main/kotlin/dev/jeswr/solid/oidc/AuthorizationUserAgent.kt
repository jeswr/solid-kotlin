package dev.jeswr.solid.oidc

import java.net.URI

/**
 * The user-interaction seam of the authorization-code flow: present
 * [authorizationURL], let the user authenticate with their provider, and return
 * the full redirect URL the provider sent the browser to.
 *
 * On Android the production implementation launches a **Chrome Custom Tab**
 * (`androidx.browser`) and resolves when the redirect-scheme `Activity`
 * receives the callback `Uri` — a thin adapter living in the app (see the
 * README), kept behind this interface so the whole OIDC flow unit-tests with a
 * stub that returns a canned callback.
 */
public fun interface AuthorizationUserAgent {
    /**
     * @param authorizationURL the provider authorization endpoint with PKCE +
     *   state query params already attached.
     * @param redirectURI the registered callback (custom scheme or https).
     * @return the full redirect URL including `code`/`state` (or `error`).
     */
    public fun authorize(authorizationURL: URI, redirectURI: URI): URI
}
