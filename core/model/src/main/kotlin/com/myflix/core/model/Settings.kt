package com.myflix.core.model

/** User-visible settings (plan.md §6.7, §8.3). The API token is stored separately and encrypted. */
data class AppSettings(
    val serverUrl: String = "",
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
    val subtitlesEnabledByDefault: Boolean = false,
    val autoplayNextEpisode: Boolean = true,
    val quality: QualityPreference = QualityPreference.AUTO,
    val frameRateMatching: Boolean = true,
    val tunneledPlayback: Boolean = true,
    val skipIntroAutomatically: Boolean = false,
) {
    val isServerConfigured: Boolean get() = serverUrl.isNotBlank()
}

/** Result of "Test connection" in Settings. */
sealed interface ConnectionTestResult {
    data class Success(val info: ServerInfo) : ConnectionTestResult
    data class Failure(val reason: String) : ConnectionTestResult
}

/** Normalises whatever the user typed into a base URL Retrofit accepts. */
object ServerUrlNormalizer {

    /**
     * Accepts `192.168.1.10`, `192.168.1.10:8096`, `http://host/path`, `host:8096/` … and returns a
     * URL with a scheme and exactly one trailing slash, or null when it cannot be salvaged.
     */
    fun normalize(input: String, defaultPort: Int? = null): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains(' ')) return null

        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val schemeEnd = withScheme.indexOf("://")
        val scheme = withScheme.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null

        val rest = withScheme.substring(schemeEnd + 3)
        if (rest.isEmpty() || rest.startsWith("/")) return null

        val hostAndPortEnd = rest.indexOf('/').let { if (it == -1) rest.length else it }
        var hostAndPort = rest.substring(0, hostAndPortEnd)
        val path = rest.substring(hostAndPortEnd).trimEnd('/')

        if (hostAndPort.isEmpty()) return null
        if (!hostAndPort.contains(':') && defaultPort != null) hostAndPort = "$hostAndPort:$defaultPort"

        val port = hostAndPort.substringAfter(':', "")
        if (port.isNotEmpty() && (port.toIntOrNull() == null || port.toInt() !in 1..65_535)) return null

        val host = hostAndPort.substringBefore(':')
        if (host.isEmpty() || host.any { it.isWhitespace() }) return null

        return "$scheme://$hostAndPort$path/"
    }
}
