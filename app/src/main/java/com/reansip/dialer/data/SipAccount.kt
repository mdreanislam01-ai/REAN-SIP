package com.reansip.dialer.data

data class SipAccount(
    val id: Long,
    var label: String = "",
    var username: String = "",
    var password: String = "",
    var domain: String = "",
    var transport: String = "UDP", // UDP | TCP | TLS
    var displayName: String = "",
    var registerEnabled: Boolean = true
) {
    fun displayLabel(): String = label.ifBlank { username.ifBlank { "Account" } }
    fun sipUri(): String = if (username.isBlank() || domain.isBlank()) "" else "sip:$username@${domain.substringBefore(':')}"
}
