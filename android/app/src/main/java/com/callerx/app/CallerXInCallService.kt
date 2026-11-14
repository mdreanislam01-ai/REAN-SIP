package com.callerx.app

import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object TelecomCallBridge {
    var service: CallerXInCallService? = null
    var currentCall: Call? = null
    var state by mutableStateOf("")
    var remoteNumber by mutableStateOf("")
    var source by mutableStateOf("SIM 1")
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

    private fun isBlocked(number: String): Boolean {
        val digits = number.filter { it.isDigit() }.takeLast(11)
        if (digits.isEmpty()) return false
        val blocked = getSharedPreferences("callerx", Context.MODE_PRIVATE).getStringSet("blocked_numbers", emptySet()) ?: emptySet()
        return blocked.any { entry ->
            val b = entry.substringBefore("|").filter { it.isDigit() }.takeLast(11)
            b.isNotEmpty() && (b == digits || (b.length >= 10 && digits.length >= 10 && b.takeLast(10) == digits.takeLast(10)))
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val incomingNumber = call.details.handle?.schemeSpecificPart.orEmpty()
        if (isBlocked(incomingNumber)) {
            try { call.reject(false) } catch (_: Exception) { try { call.disconnect() } catch (_: Exception) { } }
            return
        }
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
        val handle = call.details.accountHandle
        val telecom = getSystemService(TelecomManager::class.java)
        val account = try { handle?.let { telecom.getPhoneAccount(it) } } catch (_: Exception) { null }
        TelecomCallBridge.source = if (account?.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) == true) {
            val simSlot = try { getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty().firstOrNull { it.subscriptionId.toString() == handle?.id }?.simSlotIndex } catch (_: Exception) { null }
            "SIM ${(simSlot ?: 0) + 1}"
        } else "SIP VOIP · HD AUDIO"
        TelecomCallBridge.state = when (call.state) {
            Call.STATE_RINGING -> "Incoming cellular call"
            Call.STATE_CONNECTING, Call.STATE_DIALING -> "Calling…"
            Call.STATE_ACTIVE -> "Connected"
            Call.STATE_DISCONNECTED -> "Call ended"
            else -> "Connecting…"
        }
    }
}
