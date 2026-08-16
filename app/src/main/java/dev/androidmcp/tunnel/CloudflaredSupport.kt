package dev.androidmcp.tunnel

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Pure parsing and validation helpers for the locally-managed cloudflared flow. */
internal object CloudflaredSupport {
    private val json = Json { ignoreUnknownKeys = true }
    private val tunnelIdRegex = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        RegexOption.IGNORE_CASE,
    )
    private val loginUrlRegex = Regex("https://dash\\.cloudflare\\.com/argotunnel[^\\s\\\"'<>]*")
    private val tunnelNameRegex = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")

    fun normalizeHostname(value: String): String = value.trim().lowercase(Locale.ROOT)

    fun isValidHostname(value: String): Boolean {
        val hostname = normalizeHostname(value)
        if (hostname.length !in 4..253 || '.' !in hostname) return false

        val labels = hostname.split('.')
        if (labels.any { label ->
                label.length !in 1..63 ||
                    !label.first().isAsciiLetterOrDigit() ||
                    !label.last().isAsciiLetterOrDigit() ||
                    label.any { char -> !char.isAsciiLetterOrDigit() && char != '-' }
            }
        ) {
            return false
        }

        val topLevelDomain = labels.last()
        return topLevelDomain.length >= 2 && topLevelDomain.all { it.isAsciiLetter() }
    }

    fun isValidTunnelName(value: String): Boolean = tunnelNameRegex.matches(value.trim())

    fun findTunnelId(listOutput: String, tunnelName: String): String? {
        // cloudflared may emit informational lines before/after the JSON array.
        val start = listOutput.indexOf('[')
        val end = listOutput.lastIndexOf(']')
        if (start < 0 || end <= start) return null

        return runCatching {
            json.parseToJsonElement(listOutput.substring(start, end + 1))
                .jsonArray
                .firstOrNull { item ->
                    item.jsonObject["name"]?.jsonPrimitive?.content == tunnelName
                }
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.content
                ?.takeIf(::isTunnelId)
        }.getOrNull()
    }

    fun findTunnelId(output: List<String>): String? =
        output.asReversed().asSequence()
            .mapNotNull { tunnelIdRegex.find(it)?.value }
            .firstOrNull()

    fun extractLoginUrl(line: String): String? =
        loginUrlRegex.find(line)?.value?.trimEnd('.', ',', ')', ']')

    private fun isTunnelId(value: String): Boolean = tunnelIdRegex.matches(value)

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isAsciiLetterOrDigit(): Boolean = isAsciiLetter() || this in '0'..'9'
}
