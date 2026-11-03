package com.github.yumeyucca.yumebox.data.trafficv2

data class RecognizedHost(val key: String, val label: String)

/** RecognizedHost must be null for IP literals, malformed hosts and absent hosts. */
class ServiceResolver(
    private val classifyHost: (String) -> RecognizedHost?,
    private val aliases: Map<String, Pair<String, String>> = defaultAliases,
) {
    fun resolve(host: String?, app: AppRef): Target {
        host?.takeIf { it.isNotBlank() }?.let { value ->
            classifyHost(value)?.let { return Target(it.key, it.label, "host") }
        }
        app.packageName?.let { packageName ->
            aliases[packageName]?.let { (key, label) -> return Target(key, label, "app_fallback") }
            return Target("app:$packageName", app.label.ifBlank { packageName }, "app_fallback")
        }
        return Target("other", "其他流量", "unknown_identity")
    }

    companion object {
        const val RULE_VERSION = 1
        // Add verified alternate clients by package name; never fuzzy-match app display names.
        val defaultAliases = mapOf(
            "org.telegram.messenger" to ("service:telegram" to "Telegram"),
            "org.telegram.messenger.web" to ("service:telegram" to "Telegram"),
            "com.google.android.youtube" to ("service:youtube" to "YouTube"),
        )
    }
}
