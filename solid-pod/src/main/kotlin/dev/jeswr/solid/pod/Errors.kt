package dev.jeswr.solid.pod

import java.net.URI

/**
 * Typed failures for pod IO. The HTTP statuses apps must branch on
 * (401/403/404/412) are distinct subclasses with actionable messages.
 */
public sealed class PodException(message: String) : Exception(message) {
    /** 401 — the request reached the server without (valid) credentials. */
    public class AuthenticationRequired(public val url: URI) :
        PodException(
            "Authentication required for $url (401). No valid credentials reached the server — " +
                "pass an authenticated HttpClient (SolidSession.authenticatedClient) to SolidPodClient.",
        )

    /** 403 — authenticated, but this WebID lacks the required access mode. */
    public class Forbidden(public val url: URI) :
        PodException(
            "Access denied for $url (403). You are authenticated but this WebID lacks the " +
                "required access mode — the resource owner must grant access (WebAccessControl.grant).",
        )

    /** 404 — the resource does not exist (reads resolve as `exists == false`). */
    public class NotFound(public val url: URI) :
        PodException(
            "Resource not found: $url (404). Create it first (write with ifMatch = null performs " +
                "a create), or ensure the parent container exists (ensureContainer).",
        )

    /** 412 — the conditional request failed (stale ETag, or already exists). */
    public class PreconditionFailed(public val url: URI) :
        PodException(
            "Conflict writing $url (412). The resource changed since it was read (stale ETag) or " +
                "already exists (If-None-Match: * create). Re-read, re-apply the change, and retry.",
        )

    /** Any other non-success status. */
    public class HttpFailure(public val status: Int, public val url: URI, public val detail: String) :
        PodException("Request to $url failed: HTTP $status. $detail")

    /** The response body could not be parsed as RDF. */
    public class UnparsableResource(public val url: URI, public val reason: String) :
        PodException("Could not parse $url as RDF: $reason")

    /** A container operation was attempted on a non-container URL. */
    public class NotAContainer(public val url: URI) :
        PodException("$url is not a container URL (it must end with \"/\").")

    /** No storage could be discovered for the WebID by any strategy. */
    public class StorageNotFound(public val webID: URI) :
        PodException(
            "No storage found for $webID. The profile advertises no pim:storage, the type index " +
                "gave no usable containers, and no ancestor declared itself a pim:Storage. The pod " +
                "owner should add a pim:storage triple to their WebID profile.",
        )

    /** The server did not advertise an ACL, or no effective ACL exists. */
    public class AclDiscoveryFailed(public val resource: URI, public val reason: String) :
        PodException("Cannot manage access for $resource: $reason")

    /** The resource is governed by ACP; this library authors WAC (`.acl`) only. */
    public class AcpNotSupported(public val resource: URI) :
        PodException("This server governs $resource with ACP (an .acr document); SolidPod authors WAC (.acl) only.")

    public companion object {
        /** Map a non-success status to the matching typed error. */
        public fun forStatus(status: Int, url: URI, detail: String = ""): PodException =
            when (status) {
                401 -> AuthenticationRequired(url)
                403 -> Forbidden(url)
                404 -> NotFound(url)
                412 -> PreconditionFailed(url)
                else -> HttpFailure(status, url, detail)
            }
    }
}
