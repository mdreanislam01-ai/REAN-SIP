package com.reansip.dialer.ui.call

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.reansip.dialer.R
import com.reansip.dialer.core.CoreManager
import com.reansip.dialer.databinding.ActivityCallBinding
import org.linphone.core.Call

/** In-call controls: mute, speaker, hold, DTMF and live media quality. */
class CallActivity : AppCompatActivity(), CoreManager.Events {

    private lateinit var binding: ActivityCallBinding
    private val handler = Handler(Looper.getMainLooper())
    private var speakerOn = false
    private var held = false
    private var connectedAt = 0L

    private val refreshRunnable = object : Runnable {
        override fun run() {
            val call = CoreManager.currentCall()
            if (call == null) {
                finish()
                return
            }
            updateCall(call)
            handler.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindUi()
        CoreManager.currentCall()?.let { updateCall(it) } ?: finish()
    }

    override fun onResume() {
        super.onResume()
        CoreManager.addListener(this)
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        CoreManager.removeListener(this)
        super.onPause()
    }

    private fun bindUi() = with(binding) {
        buttonEndCall.setOnClickListener { CoreManager.hangup(CoreManager.currentCall()) }
        buttonMute.setOnClickListener {
            val nextMuted = !CoreManager.isMicMuted()
            CoreManager.setMicMuted(nextMuted)
            buttonMute.setText(if (nextMuted) R.string.unmute else R.string.mute)
            buttonMute.isSelected = nextMuted
        }
        buttonSpeaker.setOnClickListener {
            speakerOn = !speakerOn
            CoreManager.currentCall()?.let { CoreManager.routeSpeaker(it, speakerOn) }
            buttonSpeaker.isSelected = speakerOn
        }
        buttonHold.setOnClickListener {
            CoreManager.currentCall()?.let { call ->
                held = CoreManager.toggleHold(call)
                buttonHold.setText(if (held) R.string.resume else R.string.hold)
                buttonHold.isSelected = held
            }
        }
        buttonKeypad.setOnClickListener {
            dtmfPanel.visibility = if (dtmfPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            buttonKeypad.isSelected = dtmfPanel.visibility == View.VISIBLE
        }
        val buttons = mapOf(
            dtmf1 to '1', dtmf2 to '2', dtmf3 to '3',
            dtmf4 to '4', dtmf5 to '5', dtmf6 to '6',
            dtmf7 to '7', dtmf8 to '8', dtmf9 to '9',
            dtmfStar to '*', dtmf0 to '0', dtmfHash to '#'
        )
        buttons.forEach { (b, digit) -> b.setOnClickListener { CoreManager.currentCall()?.let { CoreManager.sendDtmf(it, digit) } } }
    }

    private fun updateCall(call: Call) = with(binding) {
        val remote = call.remoteAddress
        val number = remote.username ?: remote.asStringUriOnly()
        callName.text = remote.displayName?.takeIf { it.isNotBlank() } ?: number
        callNumber.text = if (remote.displayName.isNullOrBlank()) "" else number

        when (call.state) {
            Call.State.IncomingReceived -> callState.setText(R.string.incoming_call)
            Call.State.OutgoingInit, Call.State.OutgoingProgress -> callState.setText(R.string.calling)
            Call.State.OutgoingRinging -> callState.setText(R.string.ringing)
            Call.State.Connected, Call.State.StreamsRunning -> {
                callState.setText(R.string.in_call)
                if (connectedAt == 0L) connectedAt = System.currentTimeMillis()
            }
            Call.State.Paused, Call.State.Pausing -> {
                callState.setText(R.string.hold)
                held = true
                buttonHold.setText(R.string.resume)
            }
            Call.State.End, Call.State.Error, Call.State.Released -> {
                callState.setText(R.string.call_ended)
                handler.postDelayed({ finish() }, 450)
            }
            else -> callState.setText(R.string.connecting)
        }
        val elapsed = if (connectedAt == 0L) 0 else ((System.currentTimeMillis() - connectedAt) / 1000L).toInt()
        callTimer.text = String.format("%02d:%02d", elapsed / 60, elapsed % 60)
        updateQuality(call)
    }

    private fun updateQuality(call: Call) = with(binding) {
        val quality = CoreManager.callQuality(call)
        val (text, color) = when {
            quality >= 3.8f -> getString(R.string.quality_excellent) to R.color.success
            quality >= 3.0f -> getString(R.string.quality_good) to R.color.success
            quality >= 2.0f -> getString(R.string.quality_fair) to R.color.warning
            else -> getString(R.string.quality_poor) to R.color.danger
        }
        qualityText.text = "${getString(R.string.quality)}: $text"
        qualityDot.setBackgroundColor(ContextCompat.getColor(this@CallActivity, color))
        codecText.text = CoreManager.currentCodec(call).ifBlank { "—" }
    }

    override fun onCallStateChanged(call: Call, state: Call.State, message: String) {
        updateCall(call)
    }
}
