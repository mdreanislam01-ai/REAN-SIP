package com.callerx.app

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object TelecomCallBridge {
    var service: CallerXInCallService? = null
    var currentCall: Call? = null
    var state by mutableStateOf("")
    var remoteNumber by mutableStateOf("")
    var launchCallUi by mutableStateOf(false)
}

class CallerXInCallService : InCallService() {
    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            updateCallUi(call)
        }
        override fun onDetailsChanged(call: Call, details: Call.Details) {
            updateCallUi(call)
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        TelecomCallBridge.service = this
        TelecomCallBridge.currentCall = call
        call.registerCallback(callback)
        updateCallUi(call)
        TelecomCallBridge.launchCallUi = true
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("callerx_open_call", true))
        } catch (_: Exception) { }
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        if (TelecomCallBridge.currentCall == call) {
            TelecomCallBridge.currentCall = null
            TelecomCallBridge.service = null
            TelecomCallBridge.state = "Call ended"
        }
        super.onCallRemoved(call)
    }

    private fun updateCallUi(call: Call) {
        TelecomCallBridge.remoteNumber = call.details.handle?.schemeSpecificPart.orEmpty()
        TelecomCallBridge.state = when (call.state) {
            Call.STATE_RINGING -> "Incoming cellular call"
            Call.STATE_CONNECTING, Call.STATE_DIALING -> "Calling…"
            Call.STATE_ACTIVE -> "Connected"
            Call.STATE_DISCONNECTED -> "Call ended"
            else -> "Connecting…"
        }
    }
}
