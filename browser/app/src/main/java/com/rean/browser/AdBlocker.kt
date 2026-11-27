package com.rean.browser

/**
 * Lightweight host-based ad/tracker blocker. Requests to a blocked host (or any of its
 * subdomains) are answered with an empty response. Extend [blockedHosts] or later load
 * a filter list such as EasyList/uBlock lists.
 */
object AdBlocker {

    private val blockedHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "adservice.google.com",
        "taboola.com",
        "outbrain.com",
        "scorecardresearch.com",
        "criteo.com",
        "adnxs.com",
        "pubmatic.com",
        "rubiconproject.com",
        "hotjar.com",
        "moatads.com",
        "amazon-adsystem.com",
        "popads.net",
        "popcash.net",
        "adsterra.com",
        "propellerads.com",
        "exoclick.com",
        "mgid.com"
    )

    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        var h = host.lowercase()
        while (true) {
            if (h in blockedHosts) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }
}
