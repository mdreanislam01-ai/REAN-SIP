package com.reansip.dialer.ui.incoming

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.reansip.dialer.core.CoreManager
import com.reansip.dialer.core.ReanCoreService
import com.reansip.dialer.databinding.ActivityIncomingCallBinding
import com.reansip.dialer.ui.call.CallActivity
import org.linphone.core.Call

/** Full-screen lock-screen-safe UI launched for an incoming SIP INVITE. */
class IncomingCallActivity : AppCompatActivity(), CoreManager.Events {

    companion object {
        const val EXTRA_NUMBER = "number"
        const val EXTRA_NAME = "name"
    }

    private lateinit var binding: ActivityIncomingCallBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val call = CoreManager.currentCall()
        val number = intent.getStringExtra(EXTRA_NUMBER)
            ?: call?.remoteAddress?.username
            ?: ""
        val name = intent.getStringExtra(EXTRA_NAME)
            ?: call?.remoteAddress?.displayName
            ?: ""
        binding.incomingName.text = name.ifBlank { number }
        binding.incomingNumber.text = if (name.isBlank()) "" else number

        binding.buttonAnswer.setOnClickListener { answer() }
        binding.buttonDecline.setOnClickListener { decline() }
    }

    override fun onResume() {
        super.onResume()
        CoreManager.addListener(this)
        val call = CoreManager.currentCall()
        if (call == null || call.state != Call.State.IncomingReceived) finish()
    }

    override fun onPause() {
        CoreManager.removeListener(this)
        super.onPause()
    }

    private fun answer() {
        val call = CoreManager.currentCall() ?: run { finish(); return }
        ReanCoreService.get()?.onCallAnsweredFromUi()
        CoreManager.answer(call)
        startActivity(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun decline() {
        CoreManager.hangup(CoreManager.currentCall())
        finish()
    }

    override fun onCallStateChanged(call: Call, state: Call.State, message: String) {
        when (state) {
            Call.State.End, Call.State.Error, Call.State.Released, Call.State.Connected, Call.State.StreamsRunning -> finish()
            else -> Unit
        }
    }
}
