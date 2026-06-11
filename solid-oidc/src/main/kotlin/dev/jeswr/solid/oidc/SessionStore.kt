package dev.jeswr.solid.oidc

import java.util.concurrent.ConcurrentHashMap

/**
 * Persistence seam for session state (tokens + DPoP key). On Android, back this
 * with `androidx.security:security-crypto` EncryptedSharedPreferences (see the
 * README); tests use [InMemorySessionStore]. The stored payload is opaque bytes.
 */
public interface SessionStore {
    public fun save(key: String, data: ByteArray)
    public fun load(key: String): ByteArray?
    public fun delete(key: String)
}

/** Non-persistent store for tests and previews. */
public class InMemorySessionStore : SessionStore {
    private val storage = ConcurrentHashMap<String, ByteArray>()

    override fun save(key: String, data: ByteArray) {
        storage[key] = data
    }

    override fun load(key: String): ByteArray? = storage[key]

    override fun delete(key: String) {
        storage.remove(key)
    }
}
