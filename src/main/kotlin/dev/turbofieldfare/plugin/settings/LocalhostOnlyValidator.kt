package dev.turbofieldfare.plugin.settings

/**
 * Decides whether a configured server host is on this machine.
 *
 * The plugin refuses any non-loopback host. This is not a style preference: the
 * TurboFieldfare server has no authentication and no TLS, so pointing this plugin
 * at a remote address would send the user's source code across a network in
 * plaintext to an unauthenticated endpoint — and would quietly turn an
 * "everything stays local" tool into one that does not. There is deliberately no
 * override setting, because an override is what an attacker (or a careless
 * copy-paste of someone's config) would flip.
 *
 * Rejecting is done on the string the user typed, before it is ever used to build
 * a URL, and there is no DNS lookup: a name that resolves to loopback today can
 * resolve elsewhere tomorrow, so only literal loopback forms are accepted.
 */
object LocalhostOnlyValidator {

    /** Hostnames accepted verbatim (case-insensitive). */
    private val LOOPBACK_NAMES = setOf("localhost", "ip6-localhost", "ip6-loopback")

    private val IPV4_LOOPBACK = Regex("""127\.\d{1,3}\.\d{1,3}\.\d{1,3}""")

    fun isLocalhost(host: String): Boolean {
        val trimmed = host.trim().removeSurrounding("[", "]").lowercase()
        if (trimmed.isEmpty()) return false
        if (trimmed in LOOPBACK_NAMES) return true
        if (trimmed == "::1" || trimmed == "0:0:0:0:0:0:0:1") return true
        if (!IPV4_LOOPBACK.matches(trimmed)) return false
        // Regex above allows 127.999.1.1; check the octets really are octets.
        return trimmed.split(".").drop(1).all { it.toInt() in 0..255 }
    }

    /** Human-readable reason, or `null` when [host] is acceptable. */
    fun reject(host: String): String? =
        if (isLocalhost(host)) {
            null
        } else {
            "Host must be on this machine (127.0.0.1, localhost or ::1). " +
                "The TurboFieldfare server has no authentication or TLS, so this plugin " +
                "will not send your code to any other host."
        }
}
