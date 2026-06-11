package dev.jeswr.solid.pod

import dev.jeswr.solid.oidc.HttpRequest
import dev.jeswr.solid.rdf.Term
import java.net.URI

/** How a storage root was discovered. */
public enum class StorageSource { PROFILE, TYPE_INDEX, LINK_HEADER }

/**
 * The storages advertised for a WebID. When [storages] has more than one
 * element the app must let the user choose — never silently take the first.
 */
public class StorageDiscovery(
    public val storages: List<URI>,
    public val source: StorageSource,
    public val webID: URI,
)

/**
 * Discover the storage root(s) for a WebID.
 *
 * Strategies in order: `pim:storage` triples on the profile; the type index
 * (registered containers probed for their storage root); the
 * `Link: <pim:Storage>; rel="type"` walk up from the profile document. Never
 * string-mangles the WebID into a pod path.
 */
public fun SolidPodClient.discoverStorage(webID: URI): StorageDiscovery {
    val profile = readResource(webID)
    if (!profile.exists) throw PodException.NotFound(webID)
    val me = Term.IRI(webID.toString())

    // 1. pim:storage on the profile.
    val fromProfile = profile.graph
        .iriObjects(me, Term.IRI(Vocab.PIM_STORAGE))
        .mapNotNull { runCatching { URI(it) }.getOrNull() }
    if (fromProfile.isNotEmpty()) {
        return StorageDiscovery(fromProfile.distinct(), StorageSource.PROFILE, webID)
    }

    // 2. Type-index fallback.
    val indexURLs = (
        profile.graph.iriObjects(me, Term.IRI(Vocab.SOLID_PUBLIC_TYPE_INDEX)) +
            profile.graph.iriObjects(me, Term.IRI(Vocab.SOLID_PRIVATE_TYPE_INDEX))
        ).mapNotNull { runCatching { URI(it) }.getOrNull() }
    val fromTypeIndex = ArrayList<URI>()
    for (indexURL in indexURLs) {
        val index = runCatching { readResource(indexURL) }.getOrNull()?.takeIf { it.exists } ?: continue
        val registrations = index.graph.subjects(
            Term.IRI(Vocab.RDF_TYPE),
            Term.IRI(Vocab.SOLID_TYPE_REGISTRATION),
        )
        for (registration in registrations) {
            val candidates = (
                index.graph.iriObjects(registration, Term.IRI(Vocab.SOLID_INSTANCE_CONTAINER)) +
                    index.graph.iriObjects(registration, Term.IRI(Vocab.SOLID_INSTANCE))
                ).mapNotNull { runCatching { URI(it) }.getOrNull() }
            for (candidate in candidates) {
                probeStorageRoot(candidate)?.let { fromTypeIndex.add(it) }
            }
        }
    }
    if (fromTypeIndex.isNotEmpty()) {
        return StorageDiscovery(fromTypeIndex.distinct(), StorageSource.TYPE_INDEX, webID)
    }

    // 3. Solid Protocol storage-discovery walk from the profile itself.
    probeStorageRoot(webID)?.let {
        return StorageDiscovery(listOf(it), StorageSource.LINK_HEADER, webID)
    }

    throw PodException.StorageNotFound(webID)
}

/**
 * Walk from [resource] up its path hierarchy, returning the first URL
 * advertising `Link: <pim:Storage>; rel="type"`, or null.
 */
public fun SolidPodClient.probeStorageRoot(resource: URI): URI? {
    for (candidate in ancestors(resource)) {
        val response = runCatching {
            httpClient.send(HttpRequest("HEAD", candidate.toString()))
        }.getOrNull() ?: return null
        val types = LinkHeader.targets(response.header("Link"), rel = "type", base = candidate)
        if (types.any { it.toString() == Vocab.PIM_STORAGE_CLASS }) return candidate
    }
    return null
}
