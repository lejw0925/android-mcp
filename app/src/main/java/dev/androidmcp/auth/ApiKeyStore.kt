package dev.androidmcp.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

private val Context.apiKeyStore by preferencesDataStore(name = "api_keys")

@Serializable
data class ApiKeyMeta(
    val id: String,
    val label: String,
    val sha256: String,
    val createdAt: Long,
    val enabled: Boolean = true,
    val lastUsedAt: Long? = null,
)

/** 新建的 key，plaintext 只在此处出现一次，UI 展示后即丢弃。 */
data class NewApiKey(val meta: ApiKeyMeta, val plaintext: String)

@Singleton
class ApiKeyStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.apiKeyStore
    private val json = Json { ignoreUnknownKeys = true }
    private val secureRandom = SecureRandom()

    val keys = MutableStateFlow<List<ApiKeyMeta>>(emptyList())

    /** 首启自动创建 / 手动创建后待展示的明文 key。 */
    private val _pendingNewKey = MutableStateFlow<NewApiKey?>(null)
    val pendingNewKey: StateFlow<NewApiKey?> = _pendingNewKey

    suspend fun ensureDefaultKey() {
        val existing = loadKeys()
        if (existing.isEmpty()) {
            create("default")
        } else {
            keys.value = existing
        }
    }

    suspend fun create(label: String): NewApiKey {
        val plaintext = generatePlaintext()
        val meta = ApiKeyMeta(
            id = randomId(),
            label = label.ifBlank { "key-${System.currentTimeMillis() % 10000}" },
            sha256 = sha256(plaintext),
            createdAt = System.currentTimeMillis(),
        )
        val updated = loadKeys() + meta
        persist(updated)
        return NewApiKey(meta, plaintext).also { _pendingNewKey.value = it }
    }

    fun consumePendingNewKey() {
        _pendingNewKey.value = null
    }

    suspend fun revoke(id: String) {
        persist(loadKeys().filterNot { it.id == id })
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        persist(loadKeys().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    suspend fun rename(id: String, label: String) {
        persist(loadKeys().map { if (it.id == id) it.copy(label = label) else it })
    }

    /** 校验 Bearer token，命中且启用则返回 meta（并更新 lastUsedAt），否则 null。 */
    suspend fun validate(token: String): ApiKeyMeta? {
        val hash = sha256(token)
        val all = loadKeys()
        val hit = all.firstOrNull { it.enabled && it.sha256 == hash } ?: return null
        val touched = hit.copy(lastUsedAt = System.currentTimeMillis())
        persist(all.map { if (it.id == hit.id) touched else it })
        return touched
    }

    private suspend fun loadKeys(): List<ApiKeyMeta> {
        val raw = store.data.first()[KEY_LIST] ?: return emptyList()
        return runCatching { json.decodeFromString<List<ApiKeyMeta>>(raw) }.getOrDefault(emptyList())
    }

    private suspend fun persist(list: List<ApiKeyMeta>) {
        store.edit { it[KEY_LIST] = json.encodeToString(list) }
        keys.update { list }
    }

    private fun generatePlaintext(): String {
        val bytes = ByteArray(32).also { secureRandom.nextBytes(it) }
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        return "amcp_$b64"
    }

    private fun randomId(): String {
        val bytes = ByteArray(6).also { secureRandom.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val KEY_LIST = stringPreferencesKey("api_key_list")
    }
}
