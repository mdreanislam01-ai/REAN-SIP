package com.callerx.app

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * Rejects blocked / wildcard-matched numbers *before* the phone rings.
 * Only active when Android has granted CallerX the call-screening role
 * (the system asks for it once CallerX is the default dialer), and only
 * when "Block before ringing" is enabled in Settings.
 */
class CallerXScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val response = CallResponse.Builder()
        try {
            val prefs = getSharedPreferences("callerx", MODE_PRIVATE)
            val enabled = prefs.getBoolean("setting:screening", true)
            val number = callDetails.handle?.schemeSpecificPart.orEmpty()
            if (!enabled || number.isBlank()) {
                respondToCall(callDetails, response.build())
                return
            }
            if (isBlockedNumber(this, number)) {
                response.setDisallowCall(true)
                response.setRejectCall(true)
                response.setSkipNotification(false)
                response.setSilenceCall(false)
            }
        } catch (_: Exception) { }
        try { respondToCall(callDetails, response.build()) } catch (_: Exception) { }
    }
}
