package com.reansip.dialer.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.reansip.dialer.data.CallLogStore
import com.reansip.dialer.data.RecentCall
import com.reansip.dialer.data.SipAccount
import com.reansip.dialer.data.SipAccountStore
import org.linphone.core.Account
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the Linphone Core lifecycle: transports, registration of the active SIP
 * account, outgoing calls, and dispatching call/registration events to the UI.
 */
object CoreManager {

    private const val TAG = "CoreManager"

    var core: Core? = null
        private set

    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Last known registration snapshot so any screen can render it instantly.
    @Volatile
    var lastRegState: RegistrationState = RegistrationState.None
        private set
    @Volatile
    var lastRegMessage: String = ""
        private set

    // ---- Lightweight event bus ------------------------------------------------

    interface Events {
        fun onRegistrationStateChanged(state: RegistrationState, message: String) {}
        fun onCallStateChanged(call: Call, state: Call.State, message: String) {}
        fun onRecentsChanged() {}
    }

    private val listeners = CopyOnWriteArrayList<Events>()
    fun addListener(l: Events) {
        listeners.addIfAbsent(l)
    }
    fun removeListener(l: Events) {
        listeners.remove(l)
    }

    // ---- Core lifecycle -------------------------------------------------------

    fun ensureCore(context: Context): Core {
        core?.let { return it }
        appContext = context.applicationContext
        val factory = Factory.instance()
        val c = factory.createCore(null, null, appContext)
        c.addListener(coreListener)
        try {
            val t = c.transports
            t.udpPort = -1   // let the OS pick a local port
            t.tcpPort = -1
            t.tlsPort = -1
            c.transports = t
        } catch (t: Throwable) {
            Log.w(TAG, "transport config failed", t)
        }
        core = c
        return c
    }

    /** Re-create the runtime account set from storage and (re)start the core. */
    @Synchronized
    fun applyAccounts(context: Context) {
        val c = ensureCore(context)
        val store = SipAccountStore(context)

        try {
            c.defaultAccount = null
            c.clearAccounts()
            c.clearAllAuthInfo()
        } catch (t: Throwable) {
            Log.w(TAG, "clear accounts", t)
        }

        val active = store.activeAccount()
        if (active == null) {
            lastRegState = RegistrationState.None
            lastRegMessage = ""
            try { c.stop() } catch (t: Throwable) { Log.w(TAG, "stop", t) }
            stopIterating()
            return
        }

        try {
            addLinphoneAccount(c, active)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to configure account", t)
            lastRegState = RegistrationState.Failed
            lastRegMessage = t.message ?: "Invalid account"
            dispatchReg()
            return
        }

        try {
            c.start()
        } catch (t: Throwable) {
            Log.e(TAG, "core start", t)
        }
        startIterating()
    }

    fun shutdown() {
        stopIterating()
        try { core?.stop() } catch (t: Throwable) { Log.w(TAG, "shutdown", t) }
    }

    // ---- Registration ---------------------------------------------------------

    private fun addLinphoneAccount(c: Core, acc: SipAccount) {
        val factory = Factory.instance()
        val domain = normalizeDomain(acc.domain)
        val hostNoPort = domain.substringBefore(':')
        if (acc.username.isBlank() || hostNoPort.isBlank()) {
            throw IllegalArgumentException("Username and server are required")
        }

        val identity = factory.createAddress("sip:${acc.username}@$hostNoPort")
            ?: throw IllegalArgumentException("Invalid SIP identity")
        if (acc.displayName.isNotBlank()) identity.displayName = acc.displayName.trim()

        val server = factory.createAddress("sip:$domain")
            ?: throw IllegalArgumentException("Invalid SIP server address")
        server.transport = when (acc.transport.uppercase()) {
            "TCP" -> TransportType.Tcp
            "TLS" -> TransportType.Tls
            else -> TransportType.Udp
        }

        val params = c.createAccountParams()
        params.identityAddress = identity
        params.serverAddress = server
        params.isRegisterEnabled = acc.registerEnabled
        params.isOutboundProxyEnabled = true
        params.expires = 600 // re-register every 10 min — keeps NAT bindings alive

        val auth = factory.createAuthInfo(acc.username, null, acc.password, null, null, hostNoPort, null)
        c.addAuthInfo(auth)

        val account = c.createAccount(params)
        c.addAccount(account)
        c.defaultAccount = account
        lastRegState = if (acc.registerEnabled) RegistrationState.Progress else RegistrationState.Cleared
        lastRegMessage = ""
        dispatchReg()
    }

    private fun normalizeDomain(d: String): String =
        d.trim().removePrefix("sips:").removePrefix("sip:").removeSuffix("/").trim()

    // ---- Calls ----------------------------------------------------------------

    fun currentCall(): Call? {
        val c = core ?: return null
        return c.currentCall ?: c.calls.firstOrNull()
    }

    fun startCall(context: Context, rawTarget: String): Boolean {
        val c = core ?: return false
        val account = SipAccountStore(context).activeAccount() ?: return false
        val target = rawTarget.trim().replace(Regex("[\\s()\\-]"), "")
        if (target.isEmpty()) return false

        val uri = when {
            target.startsWith("sip:") || target.startsWith("sips:") -> target
            target.contains("@") -> "sip:$target"
            else -> "sip:$target@${normalizeDomain(account.domain).substringBefore(':')}"
        }
        val address = try {
            Factory.instance().createAddress(uri)
        } catch (t: Throwable) {
            null
        } ?: return false

        return try {
            val params = c.createCallParams(null) ?: return false
            params.setVideoEnabled(false)
            configureRecordingFile(params)
            c.inviteAddressWithParams(address, params)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "invite failed", t)
            false
        }
    }

    fun answer(call: Call) {
        try {
            val c = core ?: return
            val params = c.createCallParams(call) ?: return
            params.setVideoEnabled(false)
            configureRecordingFile(params)
            call.acceptWithParams(params)
        } catch (t: Throwable) {
            Log.e(TAG, "answer failed", t)
        }
    }

    fun hangup(call: Call?) {
        try { call?.terminate() } catch (t: Throwable) { Log.w(TAG, "terminate", t) }
    }

    fun terminateAll() {
        try { core?.terminateAllCalls() } catch (t: Throwable) { Log.w(TAG, "terminateAll", t) }
    }

    @Volatile
    private var micMuted = false

    fun setMicMuted(muted: Boolean) {
        micMuted = muted
        try { core?.setMicEnabled(!muted) } catch (t: Throwable) { Log.w(TAG, "mic", t) }
    }

    fun isMicMuted(): Boolean = micMuted

    @Volatile
    private var recordingRequested = false

    @Volatile
    private var recordingActive = false

    /**
     * A record file is prepared for every SIP call but no audio is written until
     * the existing Auto Record / record control in the CallerX UI enables it.
     */
    private fun configureRecordingFile(params: org.linphone.core.CallParams) {
        val ctx = appContext ?: return
        try {
            val dir = File(ctx.filesDir, "recordings").apply { mkdirs() }
            val file = File(dir, "CallerX-${System.currentTimeMillis()}.wav")
            params.setRecordFile(file.absolutePath)
        } catch (t: Throwable) {
            Log.w(TAG, "prepare recording", t)
        }
    }

    fun setRecordingEnabled(enabled: Boolean) {
        recordingRequested = enabled
        val call = currentCall() ?: return
        try {
            if (enabled && !recordingActive) {
                call.startRecording()
                recordingActive = true
            } else if (!enabled && recordingActive) {
                call.stopRecording()
                recordingActive = false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recording", t)
            recordingActive = false
        }
    }

    fun toggleRecording(): Boolean {
        setRecordingEnabled(!recordingRequested)
        return recordingRequested
    }

    /** Returns true when ON hold after the toggle. */
    fun toggleHold(call: Call): Boolean {
        return try {
            if (call.state == Call.State.Paused || call.state == Call.State.PausedByRemote) {
                call.resume()
                false
            } else {
                call.pause()
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hold", t)
            false
        }
    }

    fun routeSpeaker(call: Call, on: Boolean) {
        val c = core ?: return
        try {
            val devices = c.audioDevices.toList()
            val device = if (on) {
                devices.firstOrNull { it.type == AudioDevice.Type.Speaker }
            } else {
                devices.firstOrNull { it.type == AudioDevice.Type.Earpiece }
                    ?: devices.firstOrNull { it.type == AudioDevice.Type.Bluetooth }
            }
            if (device != null) call.outputAudioDevice = device
        } catch (t: Throwable) {
            Log.w(TAG, "route audio", t)
        }
    }

    fun sendDtmf(call: Call, digit: Char) {
        try { call.sendDtmf(digit) } catch (t: Throwable) { Log.w(TAG, "dtmf", t) }
    }

    fun callQuality(call: Call): Float {
        return try { call.currentQuality } catch (t: Throwable) { 0f }
    }

    fun currentCodec(call: Call): String {
        return try {
            call.currentParams?.usedAudioPayloadType?.mimeType ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    // ---- Iterate pump (keeps the SIP stack alive) ------------------------------

    @Volatile
    private var iterating = false

    private val iterateRunnable = object : Runnable {
        override fun run() {
            try { core?.iterate() } catch (t: Throwable) { Log.w(TAG, "iterate", t) }
            if (iterating) mainHandler.postDelayed(this, 20)
        }
    }

    private fun startIterating() {
        if (iterating) return
        iterating = true
        mainHandler.post(iterateRunnable)
    }

    private fun stopIterating() {
        iterating = false
        mainHandler.removeCallbacks(iterateRunnable)
    }

    // ---- Core -> app events ----------------------------------------------------

    private class CallMeta {
        var answered: Boolean = false
        var startMillis: Long = 0L
    }
    private val callMeta = IdentityHashMap<Call, CallMeta>()

    private val coreListener = object : CoreListenerStub() {

        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState,
            message: String
        ) {
            lastRegState = state
            lastRegMessage = message
            dispatchReg()
        }

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            when (state) {
                Call.State.IncomingReceived -> {
                    ReanCoreService.get()?.onIncomingCall(call)
                }
                Call.State.Connected, Call.State.StreamsRunning -> {
                    val meta = callMeta.getOrPut(call) { CallMeta() }
                    if (!meta.answered) {
                        meta.answered = true
                        meta.startMillis = System.currentTimeMillis()
                    }
                    if (recordingRequested && !recordingActive) setRecordingEnabled(true)
                    ReanCoreService.get()?.onCallActive()
                }
                Call.State.End, Call.State.Error -> {
                    try { if (recordingActive) call.stopRecording() } catch (_: Throwable) { }
                    recordingActive = false
                    recordingRequested = false
                    logRecent(call)
                }
                Call.State.Released -> {
                    callMeta.remove(call)
                    if (core.calls.isEmpty()) {
                        ReanCoreService.get()?.onAllCallsEnded()
                    }
                }
                else -> {}
            }
            mainHandler.post {
                listeners.forEach { it.onCallStateChanged(call, state, message) }
            }
        }
    }

    private fun dispatchReg() {
        val state = lastRegState
        val msg = lastRegMessage
        mainHandler.post {
            listeners.forEach { it.onRegistrationStateChanged(state, msg) }
        }
    }

    private fun logRecent(call: Call) {
        val ctx = appContext ?: return
        try {
            val remote = call.remoteAddress
            val number = remote.username ?: remote.asStringUriOnly()
            val name = remote.displayName ?: ""
            val incoming = call.dir == Call.Dir.Incoming
            val meta = callMeta[call]
            val answered = meta?.answered == true
            val duration = meta?.takeIf { it.answered }?.let {
                ((System.currentTimeMillis() - it.startMillis) / 1000L).toInt().coerceAtLeast(0)
            } ?: 0
            CallLogStore(ctx).add(
                RecentCall(
                    number = number,
                    displayName = name,
                    direction = if (incoming) "in" else "out",
                    missed = incoming && !answered,
                    durationSec = duration,
                    timestamp = System.currentTimeMillis()
                )
            )
            mainHandler.post { listeners.forEach { it.onRecentsChanged() } }
        } catch (t: Throwable) {
            Log.w(TAG, "log recent", t)
        }
    }
}
