package dev.androidmcp.server

/** Counts distinct API keys with one or more in-flight stateless MCP requests. */
internal class ActiveApiKeyConnections {
    private val references = mutableMapOf<String, Int>()

    @Synchronized
    fun acquire(keyId: String): Int {
        references[keyId] = references.getOrDefault(keyId, 0) + 1
        return references.size
    }

    @Synchronized
    fun release(keyId: String): Int {
        when (val current = references[keyId]) {
            null -> Unit
            1 -> references.remove(keyId)
            else -> references[keyId] = current - 1
        }
        return references.size
    }

    @Synchronized
    fun clear() {
        references.clear()
    }
}
