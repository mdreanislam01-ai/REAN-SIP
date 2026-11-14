package com.callerx.app

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telephony.SubscriptionManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.CallLog
import android.provider.Telephony
import java.text.DateFormat
import java.util.Date
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.linphone.core.*

// Theme presets (from the user's Settings HTML design). Selecting one recolors the
// whole app because CallerXApp builds its MaterialTheme colorScheme from Palette[accent].
private data class ThemePreset(val key: String, val name: String, val color: Color)
private val ThemePresets = listOf(
    ThemePreset("indigo", "Indigo Accent", Color(0xFF4F46E5)),
    ThemePreset("blue", "Royal Blue", Color(0xFF2563EB)),
    ThemePreset("teal", "Emerald Teal", Color(0xFF0D9488)),
    ThemePreset("rose", "Crimson Rose", Color(0xFFE11D48)),
    ThemePreset("amber", "Warm Amber", Color(0xFFD97706))
)
private val Palette = ThemePresets.map { it.color }

// Audio codec presets for the Audio & Codec screen (mime type used by Linphone).
private data class CodecPreset(val mime: String, val label: String, val desc: String)
private val CodecPresets = listOf(
    CodecPreset("opus", "Opus (48kHz HD)", "Best voice clarity & adaptive bitrate"),
    CodecPreset("G722", "G.722 Wideband", "HD voice for VoIP networks"),
    CodecPreset("PCMU", "PCMU (G.711 u-law)", "Standard telephony compatibility"),
    CodecPreset("PCMA", "PCMA (G.711 a-law)", "International GSM/SIP fallback"),
    CodecPreset("G729", "G.729", "Ultra-low bandwidth mobile data mode")
)

// Power modes for the Power Mode screen.
private val PowerModes = listOf(
    Triple("Unrestricted", "Unrestricted (Recommended)", "Keeps SIP VoIP online in background for instant incoming calls."),
    Triple("Optimized", "Optimized", "Balances background SIP push notifications and battery life."),
    Triple("Battery Saver", "Battery Saver", "Pauses background SIP registration when screen is locked.")
)
private data class Person(val name: String, val number: String, val initials: String, val verified: Boolean = false, val spam: Boolean = false, val time: String = "", val callType: Int = 0, val source: String = "")
private val people = emptyList<Person>()
private fun loadContacts(context: Context): List<Person> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val result = mutableListOf<Person>()
    context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE")?.use { c ->
        while (c.moveToNext()) { val name = c.getString(0).orEmpty(); val number = c.getString(1).orEmpty(); result.add(Person(name.ifBlank { number }, number, name.firstOrNull()?.toString() ?: "?")) }
    }
    return result.distinctBy { it.number }
}
private fun loadCallLog(context: Context): List<Person> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val result = mutableListOf<Person>()
    val subSlots = try { context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty().associate { it.subscriptionId.toString() to (it.simSlotIndex + 1) } } catch (_: Exception) { emptyMap() }
    context.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.PHONE_ACCOUNT_ID), null, null, CallLog.Calls.DATE + " DESC")?.use { c ->
        while (c.moveToNext()) {
            val name = c.getString(0).orEmpty(); val number = c.getString(1).orEmpty(); val date = c.getLong(2); val accountId = c.getString(4).orEmpty()
            val source = subSlots[accountId]?.let { "SIM $it" } ?: if (accountId.isNotBlank() && accountId.toIntOrNull() == null) "SIP" else "SIM 1"
            result.add(Person(name.ifBlank { number }, number, name.firstOrNull()?.toString() ?: "?", time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(date)), callType = c.getInt(3), source = source))
        }
    }
    return result
}
private fun loadSms(context: Context): List<Pair<String,String>> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val rows = mutableListOf<Pair<String,String>>()
    try {
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY), null, null, Telephony.Sms.DATE + " DESC")?.use { c -> while (c.moveToNext()) rows.add((c.getString(0).orEmpty()) to c.getString(1).orEmpty()) }
    } catch (_: SecurityException) { }
    return rows
}

class MainActivity : ComponentActivity() {
    private var core: Core? = null
    var registered by mutableStateOf(false)
    var registrationMessage by mutableStateOf("SIP Offline")
    var callState by mutableStateOf("")
    var activeNumber by mutableStateOf("")
    var activeCallName by mutableStateOf("")
    var activeCallSource by mutableStateOf("SIP VOIP · HD AUDIO")
    private var activeCall: Call? = null
    var errorMessage by mutableStateOf("")
    var dialIntentNumber by mutableStateOf("")
    var callUiRequested by mutableStateOf(false)
    var dialerRoleTick by mutableIntStateOf(0)
    var resumeTick by mutableIntStateOf(0)
    private var pendingCellNumber = ""
    private var pendingCellSlot = 0
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startSipCall(activeNumber, activeCallName) else errorMessage = "Microphone permission is needed for SIP audio calls."
    }
    private val cellPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) placeCellCall(pendingCellNumber, pendingCellSlot) else errorMessage = "Phone permission is needed to start a cellular call."
    }
    private val runtimePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) errorMessage = "Some permissions were declined. You can enable them later in Android Settings."
    }
    private val defaultDialerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        dialerRoleTick++
        if (!isDefaultDialer()) errorMessage = "Android did not set CallerX as the default dialer. Check Settings → Apps → Default apps → Phone app, then select CallerX. If CallerX is not listed, your Android device may not accept it as a dialer due to its role requirements."
    }

    fun requestSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            runtimePermissionLauncher.launch(arrayOf(Manifest.permission.READ_SMS))
        }
    }

    fun markAllSmsRead() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) { errorMessage = "Grant SMS access first."; return }
        try {
            val values = android.content.ContentValues().apply { put(Telephony.Sms.READ, 1) }
            val changed = contentResolver.update(Telephony.Sms.Inbox.CONTENT_URI, values, "${Telephony.Sms.READ}=0", null)
            errorMessage = if (changed > 0) "Marked $changed messages as read." else "There are no unread SMS messages."
        } catch (_: SecurityException) { errorMessage = "Android allows marking SMS as read only for the default SMS app. Set CallerX as your default SMS app to use this action." }
        resumeTick++
    }

    fun requestStartupPermissions() {
        val permissions = mutableListOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS, Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) runtimePermissionLauncher.launch(missing.toTypedArray())
    }

    fun isDefaultDialer(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)
        else (getSystemService(TELECOM_SERVICE) as TelecomManager).defaultDialerPackage == packageName
    }

    fun requestDefaultDialer() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (!roles.isRoleAvailable(RoleManager.ROLE_DIALER)) { errorMessage = "This Android device does not offer the dialer role to CallerX. Check your phone manufacturer's default-app settings."; return }
            if (roles.isRoleHeld(RoleManager.ROLE_DIALER)) return
            defaultDialerLauncher.launch(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        } else {
            @Suppress("DEPRECATION")
            defaultDialerLauncher.launch(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName))
        }
    }

    fun startCellCall(number: String, simSlot: Int) {
        activeCallSource = "SIM ${simSlot + 1}"
        val cleaned = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (cleaned.isBlank()) { errorMessage = "Enter a phone number first."; return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCellNumber = cleaned; pendingCellSlot = simSlot
            cellPermission.launch(Manifest.permission.CALL_PHONE)
        } else placeCellCall(cleaned, simSlot)
    }

    private fun placeCellCall(number: String, simSlot: Int) {
        try {
            val telecom = getSystemService(TelecomManager::class.java)
            val simAccounts = telecom.callCapablePhoneAccounts.filter { handle ->
                telecom.getPhoneAccount(handle)?.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) == true
            }
            if (simAccounts.isEmpty()) { errorMessage = "No active SIM calling account was found. Check that the SIM is enabled and CallerX has Phone permission."; return }
            val subscriptions = try { getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty() } catch (_: SecurityException) { emptyList() }
            val orderedAccounts = simAccounts.sortedBy { handle -> subscriptions.firstOrNull { it.subscriptionId == handle.id.toIntOrNull() }?.simSlotIndex ?: Int.MAX_VALUE }
            val subId = subscriptions.firstOrNull { it.simSlotIndex == simSlot }?.subscriptionId
            val account = if (subId != null) simAccounts.firstOrNull { it.id.toIntOrNull() == subId }
                ?: orderedAccounts.getOrNull(simSlot) else orderedAccounts.getOrNull(simSlot)
            if (account == null) { errorMessage = "SIM ${simSlot + 1} is not available. Check the SIM is inserted and enabled."; return }
            val extras = Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account) }
            telecom.placeCall(Uri.fromParts("tel", number, null), extras)
        } catch (e: Exception) { errorMessage = "Could not start SIM ${simSlot + 1} call: ${e.message ?: "check SIM and Phone permission"}" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.rgb(234, 240, 252)
        intent?.data?.schemeSpecificPart?.let { dialIntentNumber = it }
        callUiRequested = intent?.getBooleanExtra("callerx_open_call", false) == true
        initializeLinphone()
        try {
            val filter = android.content.IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
            registerReceiver(screenReceiver, filter)
        } catch (_: Exception) { }
        setContent { CallerXApp(this) }
    }

    override fun onResume() { super.onResume(); resumeTick++ }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.schemeSpecificPart?.let { dialIntentNumber = it }
        callUiRequested = intent.getBooleanExtra("callerx_open_call", false)
    }

    private fun initializeLinphone() {
        try {
            val c = Factory.instance().createCore(null, null, applicationContext)
            core = c
            c.addListener(object : CoreListenerStub() {
                override fun onAccountRegistrationStateChanged(core: Core, account: Account, state: RegistrationState, message: String) {
                    runOnUiThread {
                        registered = state == RegistrationState.Ok
                        registrationMessage = when (state) {
                            RegistrationState.Ok -> "SIP Online"
                            RegistrationState.Progress -> "Registering…"
                            RegistrationState.Failed -> "SIP Registration Failed"
                            else -> "SIP Offline"
                        }
                    }
                }
                override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
                    runOnUiThread {
                        if (state == Call.State.IncomingReceived) {
                            val remote = call.remoteAddress?.username ?: call.remoteAddressAsString.orEmpty()
                            if (isBlockedNumber(remote)) { try { call.terminate() } catch (_: Exception) { }; return@runOnUiThread }
                        }
                        if (state == Call.State.StreamsRunning) {
                            val autoRec = getSharedPreferences("callerx", Context.MODE_PRIVATE).getBoolean("auto_record", true)
                            if (autoRec && !sipRecordingActive) { try { call.startRecording(); sipRecordingActive = true } catch (_: Exception) { } }
                        }
                        if (state == Call.State.End || state == Call.State.Error || state == Call.State.Released) {
                            if (sipRecordingActive) { try { call.stopRecording() } catch (_: Exception) { }; sipRecordingActive = false }
                        }
                        activeCall = if (state == Call.State.End || state == Call.State.Error || state == Call.State.Released) null else call
                        if (state == Call.State.IncomingReceived) {
                            activeCallName = call.remoteAddress?.displayName?.takeIf { it.isNotBlank() } ?: "Incoming caller"
                            activeNumber = call.remoteAddress?.username ?: call.remoteAddressAsString.orEmpty()
                        }
                        callState = when (state) {
                            Call.State.IncomingReceived -> "Incoming SIP call"
                            Call.State.OutgoingInit, Call.State.OutgoingProgress, Call.State.OutgoingRinging -> "Calling…"
                            Call.State.Connected -> "Connected"
                            Call.State.StreamsRunning -> "In call"
                            Call.State.End, Call.State.Released -> "Call ended"
                            Call.State.Error -> "Call failed: $message"
                            else -> callState
                        }
                    }
                }
            })
            c.start()
            applyAudioSettings()
        } catch (e: Exception) {
            errorMessage = "VoIP engine couldn't start: ${e.message ?: "unknown error"}"
        }
    }

    fun registerSip(username: String, password: String, domain: String, port: String, transport: String, displayName: String = "") {
        val c = core ?: run { initializeLinphone(); core } ?: return
        try {
            c.clearAccounts()
            c.clearAllAuthInfo()
            val cleanDomain = domain.trim().removePrefix("sip:").substringBefore(';')
            val auth = Factory.instance().createAuthInfo(username.trim(), null, password, null, null, cleanDomain)
            val params = c.createAccountParams()
            val identity = Factory.instance().createAddress("sip:${username.trim()}@$cleanDomain")
            if (displayName.isNotBlank()) { try { identity?.displayName = displayName.trim() } catch (_: Exception) { } }
            val server = Factory.instance().createAddress("sip:$cleanDomain:${port.trim()};transport=${transport.lowercase()}")
            params.setIdentityAddress(identity)
            params.setServerAddress(server)
            params.setRegisterEnabled(true)
            val account = c.createAccount(params)
            c.addAuthInfo(auth)
            c.addAccount(account)
            c.defaultAccount = account
            registrationMessage = "Registering…"
            applyAudioSettings()
        } catch (e: Exception) {
            registered = false
            registrationMessage = "SIP Offline"
            errorMessage = "SIP setup failed: ${e.message ?: "check the account details"}"
        }
    }

    fun unregisterSip() {
        try {
            val c = core ?: return
            c.clearAccounts()
            c.clearAllAuthInfo()
            c.defaultAccount = null
            registered = false
            registrationMessage = "SIP Offline"
        } catch (_: Exception) { }
    }

    fun toast(msg: String) {
        try { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) { }
    }

    fun requestPermission(vararg perms: String) {
        try { runtimePermissionLauncher.launch(arrayOf(*perms)) } catch (_: Exception) { }
    }

    fun openOverlaySettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) { errorMessage = "Could not open the overlay permission settings." }
    }

    fun isBlockedNumber(number: String): Boolean {
        val digits = number.filter { it.isDigit() }.takeLast(11)
        if (digits.isEmpty()) return false
        val blocked = getSharedPreferences("callerx", Context.MODE_PRIVATE).getStringSet("blocked_numbers", emptySet()) ?: emptySet()
        return blocked.any { entry ->
            val b = entry.substringBefore("|").filter { it.isDigit() }.takeLast(11)
            b.isNotEmpty() && (b == digits || (b.length >= 10 && digits.length >= 10 && b.takeLast(10) == digits.takeLast(10)))
        }
    }

    fun recordingFiles(): List<java.io.File> {
        return try {
            val dir = java.io.File(filesDir, "recordings")
            if (!dir.exists()) emptyList()
            else dir.listFiles()?.filter { it.isFile && (it.name.endsWith(".wav", true) || it.name.endsWith(".mka", true)) }?.sortedByDescending { it.lastModified() } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private var sipRecordingActive = false

    private fun recordFileForCall(): String? {
        return try {
            val dir = java.io.File(filesDir, "recordings").apply { mkdirs() }
            java.io.File(dir, "CallerX-${System.currentTimeMillis()}.wav").absolutePath
        } catch (_: Exception) { null }
    }

    // Applies the saved Audio & Codec settings to the Linphone core.
    fun applyAudioSettings() {
        val c = core ?: return
        val prefs = getSharedPreferences("callerx", Context.MODE_PRIVATE)
        try {
            val enabled = prefs.getStringSet("audio_codecs", setOf("opus", "G722", "PCMU")) ?: setOf("opus", "G722", "PCMU")
            val enabledLower = enabled.map { it.lowercase() }.toSet()
            try {
                c.audioPayloadTypes.forEach { pt ->
                    pt.enable(enabledLower.contains(pt.mimeType.lowercase()))
                }
            } catch (_: Exception) { }
            try { c.setEchoCancellationEnabled(prefs.getBoolean("audio_echo", true)) } catch (_: Exception) { }
            try { c.config?.setInt("sound", "noise_suppression", if (prefs.getBoolean("audio_noise", true)) 1 else 0) } catch (_: Exception) { }
            try { c.micGainDb = prefs.getFloat("audio_mic_gain", 2f) } catch (_: Exception) { }
        } catch (_: Exception) { }
    }

    // Battery Saver: pause the SIP core while the screen is off, resume when it turns on.
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val mode = getSharedPreferences("callerx", Context.MODE_PRIVATE).getString("power_mode", "Unrestricted") ?: "Unrestricted"
            if (mode != "Battery Saver") return
            try {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> core?.stop()
                    Intent.ACTION_SCREEN_ON -> { core?.start(); applyAudioSettings() }
                }
            } catch (_: Exception) { }
        }
    }

    fun startSipCall(number: String, name: String) {
        activeCallSource = "SIP VOIP · HD AUDIO"
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activeNumber = number; activeCallName = name
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val c = core ?: return
        try {
            val clean = number.trim().replace(" ", "").replace("-", "")
            val domain = c.defaultAccount?.params?.identityAddress?.domain.orEmpty()
            val target = if (clean.startsWith("sip:") || clean.contains("@")) clean else "sip:$clean@$domain"
            val address = c.interpretUrl(target)
            if (address == null) { errorMessage = "Enter a valid SIP address or register a SIP account first."; return }
            activeCallName = name
            try {
                val params = c.createCallParams(null)
                params?.setVideoEnabled(false)
                recordFileForCall()?.let { params?.setRecordFile(it) }
                activeCall = if (params != null) c.inviteAddressWithParams(address, params) else c.inviteAddress(address)
            } catch (_: Exception) {
                activeCall = c.inviteAddress(address)
            }
            callState = "Calling…"
        } catch (e: Exception) { errorMessage = "Call could not start: ${e.message ?: "network unavailable"}" }
    }

    fun answerCall() {
        try {
            core?.currentCall?.let { call ->
                try {
                    val params = core?.createCallParams(call)
                    params?.setVideoEnabled(false)
                    recordFileForCall()?.let { params?.setRecordFile(it) }
                    if (params != null) call.acceptWithParams(params) else call.accept()
                } catch (_: Exception) { call.accept() }
            }
        } catch (e: Exception) { errorMessage = "Could not answer call: ${e.message}" }
    }
    fun answerCellCall() { try { TelecomCallBridge.currentCall?.answer(0) } catch (e: Exception) { errorMessage = "Could not answer call: ${e.message}" } }
    fun endCellCall() { try { TelecomCallBridge.currentCall?.disconnect() } catch (e: Exception) { errorMessage = "Could not end call: ${e.message}" } }
    fun endCall() { try { core?.currentCall?.terminate() ?: core?.terminateAllCalls() } catch (_: Exception) { core?.terminateAllCalls() } }
    fun toggleMute(muted: Boolean) {
        if (TelecomCallBridge.currentCall != null) { TelecomCallBridge.service?.setMuted(muted); return }
        core?.currentCall?.microphoneMuted = muted
    }
    fun toggleHold(held: Boolean) {
        try {
            if (TelecomCallBridge.currentCall != null) { if (held) TelecomCallBridge.currentCall?.hold() else TelecomCallBridge.currentCall?.unhold() }
            else core?.currentCall?.let { if (held) it.pause() else it.resume() }
        } catch (e: Exception) { errorMessage = "Could not ${if (held) "hold" else "resume"} call: ${e.message}" }
    }

    fun setSpeaker(enabled: Boolean) {
        if (TelecomCallBridge.currentCall != null) { TelecomCallBridge.service?.setAudioRoute(if (enabled) android.telecom.CallAudioState.ROUTE_SPEAKER else android.telecom.CallAudioState.ROUTE_EARPIECE); return }
        try {
            val c = core ?: return
            val speaker = c.audioDevices.firstOrNull { if (enabled) it.type == AudioDevice.Type.Speaker else it.type == AudioDevice.Type.Earpiece }
            if (speaker != null) c.outputAudioDevice = speaker
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        try { core?.stop() } catch (_: Exception) { }
        core = null
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallerXApp(activity: MainActivity) {
    var tab by remember { mutableStateOf("Calls") }
    var screen by remember { mutableStateOf("home") }
    var filter by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Person?>(null) }
    var contacts by remember { mutableStateOf(emptyList<Person>()) }
    var recentCalls by remember { mutableStateOf(emptyList<Person>()) }
    var messages by remember { mutableStateOf(emptyList<Pair<String,String>>()) }
    var dark by remember { mutableStateOf(false) }
    var accent by remember { mutableIntStateOf(0) }
    var sipUsername by remember { mutableStateOf("") }
    var sipPassword by remember { mutableStateOf("") }
    var sipDomain by remember { mutableStateOf("") }
    var sipDisplayName by remember { mutableStateOf("") }
    var sipPort by remember { mutableStateOf("5060") }
    var transport by remember { mutableStateOf("UDP") }
    var number by remember { mutableStateOf("") }
    var keypadOpen by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var speaker by remember { mutableStateOf(false) }
    var showCallSheet by remember { mutableStateOf(false) }
    var afterCall by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var showPermissionWelcome by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("callerx", Context.MODE_PRIVATE) }
    LaunchedEffect(activity.resumeTick) {
        contacts = loadContacts(context); recentCalls = loadCallLog(context); messages = loadSms(context)
        sipUsername = prefs.getString("user", "") ?: ""
        sipDomain = prefs.getString("domain", "") ?: ""
        sipDisplayName = prefs.getString("display_name", "") ?: ""
        sipPort = prefs.getString("port", "5060") ?: "5060"
        transport = prefs.getString("transport", "UDP") ?: "UDP"
        dark = prefs.getBoolean("dark", false)
        accent = prefs.getInt("accent", 0)
        showPermissionWelcome = !prefs.getBoolean("permission-intro-seen", false)
    }
    LaunchedEffect(activity.dialIntentNumber) { if (activity.dialIntentNumber.isNotBlank()) { number = activity.dialIntentNumber; screen = "dialer"; activity.dialIntentNumber = "" } }
    LaunchedEffect(activity.errorMessage) { if (activity.errorMessage.isNotBlank()) showError = true }
    LaunchedEffect(activity.callState, activity.callUiRequested, TelecomCallBridge.state) {
        if (activity.callState == "Incoming SIP call" || activity.callUiRequested || TelecomCallBridge.state == "Incoming cellular call") {
            screen = "call"
            if (TelecomCallBridge.currentCall != null) { activity.activeNumber = TelecomCallBridge.remoteNumber; activity.activeCallName = "Phone call"; activity.activeCallSource = TelecomCallBridge.source }
            activity.callUiRequested = false
        }
    }
    // "Ask before each call": when off, call straight away with the default line
    // (registered SIP first, then SIM 1, then SIM 2) instead of showing the sheet.
    fun startCallFlow(person: Person) {
        val ask = prefs.getBoolean("setting:Ask before each call", true)
        if (!ask) {
            val dialed = person.number
            if (activity.registered) {
                activity.activeNumber = dialed; activity.activeCallName = person.name
                activity.startSipCall(dialed, person.name); screen = "call"
            } else if (prefs.getBoolean("setting:SIM 1", true)) {
                activity.startCellCall(dialed, 0); screen = "home"
            } else if (prefs.getBoolean("setting:SIM 2", true)) {
                activity.startCellCall(dialed, 1); screen = "home"
            } else {
                activity.errorMessage = "Enable SIM 1, SIM 2 or register SIP to make calls."
            }
        } else {
            selected = person
            showCallSheet = true
        }
    }
    val color = Palette[accent.coerceIn(Palette.indices)]
    val scheme = if (dark) darkColorScheme(primary = color, secondary = color, background = Color(0xFF101318), surface = Color(0xFF1B2027))
        else lightColorScheme(primary = color, secondary = color, background = Color(0xFFF6F8FA), surface = Color.White)
    val isCellCall = TelecomCallBridge.currentCall != null
    val visibleCallState = if (isCellCall) TelecomCallBridge.state else activity.callState
    val visibleCallName = if (isCellCall) "Phone call" else activity.activeCallName.ifBlank { selected?.name ?: "CallerX contact" }
    val visibleCallNumber = if (isCellCall) TelecomCallBridge.remoteNumber else activity.activeNumber.ifBlank { selected?.number ?: number }
    MaterialTheme(colorScheme = scheme) {
        if (screen == "call") {
            NativeCallScreen(visibleCallName, visibleCallNumber, visibleCallState, activity.activeCallSource, dark, muted, speaker,
                onTheme = { dark = it; prefs.edit().putBoolean("dark", it).apply() },
                onMute = { muted = !muted; activity.toggleMute(muted) }, onSpeaker = { speaker = !speaker; activity.setSpeaker(speaker) },
                onHold = { held -> activity.toggleHold(held) }, onAnswer = { if (isCellCall) activity.answerCellCall() else activity.answerCall() },
                onDecline = { if (isCellCall) activity.endCellCall() else activity.endCall(); screen = "home" },
                onEnd = { if (isCellCall) activity.endCellCall() else activity.endCall(); afterCall = true; screen = "summary" })
        } else if (screen == "summary") {
            SummaryScreen(activity.activeCallName, activity.activeNumber, onDone = { screen = "home"; tab = "Calls" })
        } else if (screen == "sip") {
            SipScreen(activity.registrationMessage, sipUsername, { sipUsername = it }, sipPassword, { sipPassword = it }, sipDomain, { sipDomain = it }, sipPort, { sipPort = it }, transport, { transport = it }, sipDisplayName, { sipDisplayName = it },
                onBack = { screen = "settings" }, onRegister = {
                    prefs.edit().putString("user", sipUsername).putString("domain", sipDomain).putString("port", sipPort).putString("transport", transport).putString("display_name", sipDisplayName).apply()
                    activity.registerSip(sipUsername, sipPassword, sipDomain, sipPort, transport, sipDisplayName)
                }, onUnregister = { activity.unregisterSip(); activity.toast("SIP Account Unregistered") })
        } else if (screen == "dialer") {
            DialerScreen(number, { number = it }, onBack = { screen = "home" }, onCall = { startCallFlow(Person("Dialed number", number, "?")) })
        } else if (screen == "profile") {
            ProfileScreen(onBack = { screen = "home" }, onTheme = { screen = "theme" }, onSettings = { screen = "settings" })
        } else if (screen == "audio") {
            AudioScreen(activity, onBack = { screen = "settings" })
        } else if (screen == "recordings") {
            RecordingsScreen(activity, onBack = { screen = "settings" })
        } else if (screen == "permissions") {
            PermissionsScreen(activity, onBack = { screen = "settings" })
        } else if (screen == "blocked") {
            BlockedScreen(activity, onBack = { screen = "settings" })
        } else if (screen == "power") {
            PowerScreen(activity, onBack = { screen = "settings" })
        } else if (screen == "update") {
            UpdateScreen(activity, onBack = { screen = "settings" })
        } else if (screen == "theme") {
            ThemeScreen(accent, { accent = it; prefs.edit().putInt("accent", it).apply() }, onBack = { screen = "settings" })
        } else if (screen == "settings") {
            SettingsScreen(activity, dark, { dark = it; prefs.edit().putBoolean("dark", it).apply() }, accent, onSip = { screen = "sip" }, onTheme = { screen = "theme" }, onProfile = { screen = "profile" }, onAudio = { screen = "audio" }, onRecordings = { screen = "recordings" }, onPermissions = { screen = "permissions" }, onBlocked = { screen = "blocked" }, onPower = { screen = "power" }, onUpdate = { screen = "update" }, onBack = { screen = "home" })
        } else if (selected != null) {
            ContactDetailScreen(selected!!, onBack = { selected = null }, onCall = { startCallFlow(selected!!) })
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                floatingActionButton = { },
                bottomBar = { NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("Calls" to Icons.Filled.Dialpad, "Contacts" to Icons.Filled.Contacts, "Messages" to Icons.AutoMirrored.Filled.Message, "Settings" to Icons.Filled.Settings).forEach { (label, icon) ->
                        NavigationBarItem(selected = tab == label, onClick = { tab = label; searchOpen = false; if (label != "Calls") keypadOpen = false }, icon = { Icon(icon, if (label == "Calls") "Keypad" else label) }, label = { Text(if (label == "Calls") "Keypad" else label) }, alwaysShowLabel = true)
                    }
                } }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (tab == "Settings") {
                        SettingsScreen(activity, dark, { dark = it; prefs.edit().putBoolean("dark", it).apply() }, accent, onSip = { screen = "sip" }, onTheme = { screen = "theme" }, onProfile = { screen = "profile" }, onAudio = { screen = "audio" }, onRecordings = { screen = "recordings" }, onPermissions = { screen = "permissions" }, onBlocked = { screen = "blocked" }, onPower = { screen = "power" }, onUpdate = { screen = "update" }, onBack = null)
                    } else {
                        Header(tab, activity.registrationMessage, searchOpen, { searchOpen = !searchOpen }, query, { query = it }, { screen = "profile" }, { screen = "sip" })
                        if (tab == "Calls") {
                            Box(Modifier.weight(1f)) {
                                Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text("Recents", fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.4).sp, color = MaterialTheme.colorScheme.primary); Text("SIM 1, SIM 2 & SIP Call History", fontSize = 11.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp)) }
                                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) { Icon(Icons.Filled.Search, "Search calls", tint = Color(0xFF64748B)) }
                                IconButton(onClick = { filter = "All"; query = ""; searchOpen = false }) { Icon(Icons.Filled.Refresh, "Reset filters", tint = Color(0xFF64748B)) }
                            }
                            if (searchOpen) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), singleLine = true, placeholder = { Text("Search name or number") }, leadingIcon = { Icon(Icons.Filled.Search, null) })
                            Row(Modifier.fillMaxWidth().background(Color(0xFFF8FAFC)).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("All", "Missed", "Received", "Outgoing").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label, maxLines = 1, fontSize = 12.sp) }, shape = RoundedCornerShape(18.dp)) }
                            }
                            val filtered = recentCalls.filter { person ->
                                val categoryMatches = when (filter) { "Outgoing" -> person.callType == CallLog.Calls.OUTGOING_TYPE; "Received" -> person.callType == CallLog.Calls.INCOMING_TYPE; "Missed" -> person.callType == CallLog.Calls.MISSED_TYPE; else -> true }
                                categoryMatches && (query.isBlank() || person.name.contains(query, true) || person.number.contains(query, true))
                            }
                            LazyColumn(Modifier.weight(if (keypadOpen) .55f else 1f), contentPadding = PaddingValues(bottom = 4.dp)) { if (filtered.isEmpty()) item { Text("No recent calls found on this device.", Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }; items(filtered) { person -> CallRow(person, color, onAvatar = { selected = person }, onCall = { startCallFlow(person) }) } }
                            if (keypadOpen) KeypadPanel(number, onNumber = { number = it }, onCall = { startCallFlow(Person("Dialed number", number, "?")) }, onHide = { keypadOpen = false })
                                }
                                if (!keypadOpen) FloatingActionButton(onClick = { keypadOpen = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 14.dp), shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White, elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 5.dp)) { Icon(Icons.Filled.Dialpad, "Open keypad", modifier = Modifier.size(23.dp)) }
                            }
                        } else if (tab == "Contacts") {
                            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Contacts", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) { Icon(if (searchOpen) Icons.Filled.Close else Icons.Filled.Search, if (searchOpen) "Close contact search" else "Search contacts", tint = Color(0xFF64748B)) }
                            }
                            if (searchOpen) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), singleLine = true, placeholder = { Text("Search name or number") }, leadingIcon = { Icon(Icons.Filled.Search, null) })
                            val visibleContacts = contacts.filter { query.isBlank() || it.name.contains(query, true) || it.number.contains(query, true) }
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                                item { SectionTitle("FAVORITES") }
                                items(visibleContacts.take(5)) { CallRow(it, color, onAvatar = { selected = it }, onCall = { startCallFlow(it) }) }
                                item { SectionTitle(if (query.isBlank()) "ALL CONTACTS" else "SEARCH RESULTS") }
                                if (visibleContacts.isEmpty()) item { Text("No matching contacts. Grant Contacts permission or add contacts to your device.", Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }; items(visibleContacts) { CallRow(it, color, onAvatar = { selected = it }, onCall = { startCallFlow(it) }) }
                            }
                        } else {
                            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Messages", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                                TextButton(onClick = { activity.markAllSmsRead() }) { Text("Read all", fontSize = 12.sp) }
                            }
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
                                if (messages.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) "No SMS messages found in the device inbox." else "SMS access is not granted. Android may restrict SMS inbox access unless CallerX is allowed by your device's SMS-app policy.", color = MaterialTheme.colorScheme.onSurfaceVariant); if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) TextButton(onClick = { activity.requestSmsPermission() }) { Text("Grant SMS access") } } }; itemsIndexed(messages) { i, row ->
                                    Row(Modifier.fillMaxWidth().clickable { }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Avatar(row.first.take(1), color, 48); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(row.first, fontWeight = FontWeight.SemiBold); Text(row.second, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp) }; Text(if (i == 0) "10:12" else "Yesterday", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }; HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showCallSheet && (screen == "home" || selected != null)) {
            AlertDialog(onDismissRequest = { showCallSheet = false }, title = { Text("Call with…") }, text = { Column { Text(selected?.number ?: number, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(14.dp)); (listOf("SIP / VoIP") + listOf("SIM 1", "SIM 2").filter { prefs.getBoolean("setting:$it", true) }).forEach { method -> TextButton(onClick = {
                showCallSheet = false
                val dialedNumber = selected?.number ?: number
                if (method == "SIP / VoIP") { activity.activeNumber = dialedNumber; activity.activeCallName = selected?.name ?: "Dialed number"; activity.startSipCall(activity.activeNumber, activity.activeCallName); screen = "call" }
                else { activity.startCellCall(dialedNumber, if (method == "SIM 2") 1 else 0); screen = "home" }
            }) { Icon(Icons.Filled.Call, null, tint = color); Spacer(Modifier.width(10.dp)); Text(method) } } } }, confirmButton = { TextButton(onClick = { showCallSheet = false }) { Text("Cancel") } })
        }
        if (showError && activity.errorMessage.isNotBlank()) AlertDialog(onDismissRequest = { showError = false; activity.errorMessage = "" }, title = { Text("CallerX") }, text = { Text(activity.errorMessage) }, confirmButton = { TextButton(onClick = { showError = false; activity.errorMessage = "" }) { Text("OK") } })
        if (showPermissionWelcome) AlertDialog(
            onDismissRequest = { showPermissionWelcome = false; prefs.edit().putBoolean("permission-intro-seen", true).apply() },
            title = { Text("Allow CallerX permissions") },
            text = { Text("Phone access lets CallerX start SIM calls, call-log and contacts access enables device data, and microphone access enables SIP audio. Android will ask you separately for each permission.") },
            confirmButton = { TextButton(onClick = { showPermissionWelcome = false; prefs.edit().putBoolean("permission-intro-seen", true).apply(); activity.requestStartupPermissions() }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { showPermissionWelcome = false; prefs.edit().putBoolean("permission-intro-seen", true).apply() }) { Text("Not now") } }
        )
    }
}

@Composable private fun Header(tab: String, sip: String, searchOpen: Boolean, onSearch: () -> Unit, query: String, onQuery: (String) -> Unit, onProfile: () -> Unit, onSip: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primary).clickable(onClick = onProfile), contentAlignment = Alignment.Center) { Text("C", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(9.dp)); Text("CallerX", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.2).sp)
            AssistChip(onClick = onSip, label = { Text(sip, color = Color(0xFF23745C), fontSize = 11.sp, fontWeight = FontWeight.Medium) }, leadingIcon = { Icon(Icons.Filled.Circle, null, tint = if (sip == "SIP Online") Color(0xFF22A06B) else Color(0xFFD64B4B), modifier = Modifier.size(8.dp)) }, colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFFECF8F1)))
        }
    }
}

@Composable private fun KeypadPanel(number: String, onNumber: (String) -> Unit, onCall: () -> Unit, onHide: () -> Unit) {
    val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), shadowElevation = 8.dp) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text(number.ifBlank { " " }, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
                if (number.isNotEmpty()) IconButton(onClick = { onNumber(number.dropLast(1)) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Backspace, "Delete digit", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            keys.chunked(3).forEach { row -> Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) { row.forEach { (digit, letters) -> Column(Modifier.weight(1f).fillMaxHeight().clickable { onNumber(number + digit) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(digit, fontSize = 29.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 31.sp); if (letters.isNotEmpty()) Text(letters, fontSize = 9.sp, color = Color(0xFF579575), fontWeight = FontWeight.Bold, letterSpacing = 2.sp) } } } }
            Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = onHide) { Icon(Icons.Filled.Dialpad, "Hide keypad", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                FilledIconButton(onClick = onCall, modifier = Modifier.size(58.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF20A45A), contentColor = Color.White)) { Icon(Icons.Filled.Call, "Call", modifier = Modifier.size(27.dp)) }
                IconButton(onClick = { if (number.isNotEmpty()) onNumber(number.dropLast(1)) }) { Icon(Icons.Filled.Backspace, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable private fun CallRow(person: Person, color: Color, onAvatar: () -> Unit, onCall: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onCall).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(person.initials, if (person.spam) Color(0xFFC64646) else color, 48, Modifier.clickable(onClick = onAvatar))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(person.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); if (person.verified) { Spacer(Modifier.width(5.dp)); Icon(Icons.Filled.Verified, null, tint = color, modifier = Modifier.size(16.dp)) }; if (person.spam) { Spacer(Modifier.width(6.dp)); Text("Spam", color = Color(0xFFC64646), fontSize = 11.sp, fontWeight = FontWeight.Bold) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${when (person.callType) { CallLog.Calls.OUTGOING_TYPE -> "Outgoing"; CallLog.Calls.MISSED_TYPE -> "Missed"; else -> "Received" }} · ${person.source.ifBlank { "Call" }} · ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
                Text(person.number, color = if (person.callType == CallLog.Calls.MISSED_TYPE) Color(0xFFD64545) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(person.time, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .42f))
}

@Composable private fun Avatar(text: String, color: Color, size: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(size.dp).clip(CircleShape).background(Brush.linearGradient(listOf(color, color.copy(alpha = .72f)))), contentAlignment = Alignment.Center) { Text(text.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size / 2.5f).sp) }
}
@Composable private fun SectionTitle(label: String) { Text(label, Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SipScreen(status: String, user: String, onUser: (String) -> Unit, password: String, onPassword: (String) -> Unit, domain: String, onDomain: (String) -> Unit, port: String, onPort: (String) -> Unit, transport: String, onTransport: (String) -> Unit, displayName: String, onDisplayName: (String) -> Unit, onBack: () -> Unit, onRegister: () -> Unit, onUnregister: () -> Unit) {
    val registered = status == "SIP Online"
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(top = 24.dp).padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("SIP Account", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(50), color = if (registered) Color(0xFFECFDF5) else MaterialTheme.colorScheme.surfaceVariant) {
                Text(if (registered) "Registered" else "Offline", color = if (registered) Color(0xFF047857) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
            Spacer(Modifier.width(8.dp))
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 18.dp), contentPadding = PaddingValues(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Registration status", fontWeight = FontWeight.SemiBold)
                            Text(status, color = if (registered) Color(0xFF2E9C67) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                        if (registered) Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF2E9C67))
                    }
                }
            }
            item { OutlinedTextField(domain, onDomain, Modifier.fillMaxWidth(), label = { Text("SIP Server / Domain") }, placeholder = { Text("sip.example.com") }, singleLine = true) }
            item { OutlinedTextField(user, onUser, Modifier.fillMaxWidth(), label = { Text("SIP Username / Extension") }, singleLine = true) }
            item { OutlinedTextField(password, onPassword, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true) }
            item { OutlinedTextField(displayName, onDisplayName, Modifier.fillMaxWidth(), label = { Text("Display Name") }, placeholder = { Text("Your name shown to others") }, singleLine = true) }
            item { OutlinedTextField(port, onPort, Modifier.fillMaxWidth(), label = { Text("Port") }, singleLine = true) }
            item {
                Text("Transport Protocol", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf("UDP", "TCP", "TLS").forEach { FilterChip(selected = transport == it, onClick = { onTransport(it) }, label = { Text(if (it == "TLS") "TLS (Secure)" else it) }) }
                }
            }
            item { Text("Use the signaling details supplied by your SIP provider. SIP password is kept in memory for this session and is not saved.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                Button(onClick = onRegister, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Filled.CloudDone, null); Spacer(Modifier.width(8.dp)); Text("Register & Save Account")
                }
            }
            item {
                OutlinedButton(onClick = onUnregister, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626))) {
                    Text("Unregister / Go Offline")
                }
            }
        }
    }
}

@Composable private fun TopBar(title: String, onBack: () -> Unit) { Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(top = 24.dp).padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }; Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) } }

@Composable private fun SettingsScreen(
    activity: MainActivity,
    dark: Boolean,
    onDark: (Boolean) -> Unit,
    accent: Int,
    onSip: () -> Unit,
    onTheme: () -> Unit,
    onProfile: () -> Unit,
    onAudio: () -> Unit,
    onRecordings: () -> Unit,
    onPermissions: () -> Unit,
    onBlocked: () -> Unit,
    onPower: () -> Unit,
    onUpdate: () -> Unit,
    onBack: (() -> Unit)?
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("callerx", Context.MODE_PRIVATE) }
    val accentColor = Palette[accent.coerceIn(Palette.indices)]
    fun iconBg(light: Color, tint: Color) = if (dark) tint.copy(alpha = 0.16f) else light

    var sim1 by remember { mutableStateOf(prefs.getBoolean("setting:SIM 1", true)) }
    var sim2 by remember { mutableStateOf(prefs.getBoolean("setting:SIM 2", true)) }
    var ask by remember { mutableStateOf(prefs.getBoolean("setting:Ask before each call", true)) }
    var autoRec by remember { mutableStateOf(prefs.getBoolean("auto_record", true)) }

    val profileName = prefs.getString("profile_name", "Md Rean Islam") ?: "Md Rean Islam"
    val autoBackup = prefs.getBoolean("auto_backup", true)
    val sipDomain = prefs.getString("domain", "") ?: ""
    val powerMode = prefs.getString("power_mode", "Unrestricted") ?: "Unrestricted"
    val blockedCount = (prefs.getStringSet("blocked_numbers", emptySet()) ?: emptySet()).size
    val recCount = activity.recordingFiles().size
    val codecs = prefs.getStringSet("audio_codecs", setOf("opus", "G722", "PCMU")) ?: setOf("opus", "G722", "PCMU")
    val noiseOn = prefs.getBoolean("audio_noise", true)
    val codecNames = CodecPresets.filter { codecs.contains(it.mime) }.joinToString(", ") { it.label.substringBefore(" (").substringBefore(" ") }
    val audioSub = (if (codecNames.isBlank()) "PCMU" else codecNames) + " · " + if (noiseOn) "Noise suppression on" else "Standard audio"
    val themeName = ThemePresets[accent.coerceIn(ThemePresets.indices)].name

    fun setSim(which: Int, value: Boolean) {
        if (which == 1) { sim1 = value; prefs.edit().putBoolean("setting:SIM 1", value).apply() }
        else { sim2 = value; prefs.edit().putBoolean("setting:SIM 2", value).apply() }
        val s1 = if (which == 1) value else sim1
        val s2 = if (which == 2) value else sim2
        if (!s1 && !s2 && ask) {
            ask = false
            prefs.edit().putBoolean("setting:Ask before each call", false).apply()
            activity.toast("Both SIMs disabled · SIP will be used by default")
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = if (onBack != null) 4.dp else 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                Text("CallerX VoIP & Dual-SIM Preferences", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            }
            val registered = activity.registered
            val pillBg = if (registered) Color(0xFFECFDF5) else MaterialTheme.colorScheme.surfaceVariant
            val pillFg = if (registered) Color(0xFF047857) else MaterialTheme.colorScheme.onSurfaceVariant
            Surface(shape = RoundedCornerShape(50), color = pillBg, border = androidx.compose.foundation.BorderStroke(1.dp, if (registered) Color(0xFFA7F3D0) else MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.clickable(onClick = onSip)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (registered) Color(0xFF10B981) else Color(0xFF94A3B8)))
                    Spacer(Modifier.width(6.dp))
                    Text(if (registered) "SIP Registered" else "SIP Offline", color = pillFg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionTitle("ACCOUNT") }
            item {
                SettingRow(Icons.Filled.ManageAccounts, Color(0xFFDB2777), iconBg(Color(0xFFFCE7F3), Color(0xFFDB2777)),
                    "My Profile & User Account", "$profileName · Auto backup is ${if (autoBackup) "on" else "off"}", onProfile)
            }
            item { SectionTitle("VOIP & SIP") }
            item {
                SettingRow(Icons.Filled.Dns, Color(0xFF2563EB), iconBg(Color(0xFFDBEAFE), Color(0xFF2563EB)),
                    "SIP Account", (if (sipDomain.isBlank()) "No SIP account" else sipDomain) + " · " + if (activity.registered) "Registered" else "Offline", onSip)
            }
            item {
                SettingRow(Icons.Filled.GraphicEq, Color(0xFF7C3AED), iconBg(Color(0xFFEDE9FE), Color(0xFF7C3AED)),
                    "Audio & Codec", audioSub, onAudio)
            }
            item { SectionTitle("SIM CALLING & RECORDING") }
            item {
                SettingRow(Icons.Filled.Smartphone, Color(0xFF0284C7), iconBg(Color(0xFFE0F2FE), Color(0xFF0284C7)),
                    "SIM 1", if (sim1) "Show SIM 1 as a call option" else "Hidden from call prompt", {}, toggle = true, checked = sim1, onToggle = { setSim(1, it) }, numberBadge = "1")
            }
            item {
                SettingRow(Icons.Filled.Smartphone, Color(0xFF0D9488), iconBg(Color(0xFFCCFBF1), Color(0xFF0D9488)),
                    "SIM 2", if (sim2) "Show SIM 2 as a call option" else "Hidden from call prompt", {}, toggle = true, checked = sim2, onToggle = { setSim(2, it) }, numberBadge = "2")
            }
            item {
                SettingRow(Icons.Filled.SwapCalls, Color(0xFF4F46E5), iconBg(Color(0xFFE0E7FF), Color(0xFF4F46E5)),
                    "Ask before each call", if (ask) "Choose SIP or an enabled SIM" else "Disabled · Uses default line", {},
                    toggle = true, checked = ask, onToggle = { ask = it; prefs.edit().putBoolean("setting:Ask before each call", it).apply() })
            }
            item {
                SettingRow(Icons.Filled.FiberManualRecord, Color(0xFFE11D48), iconBg(Color(0xFFFFE4E6), Color(0xFFE11D48)),
                    "Auto Call Recording", if (autoRec) "On: every call is recorded" else "Off: manual recording only", onRecordings,
                    toggle = true, checked = autoRec, onToggle = { autoRec = it; prefs.edit().putBoolean("auto_record", it).apply() },
                    badge = if (recCount > 0) "$recCount files" else null, badgeBg = Color(0xFFFFF1F2), badgeFg = Color(0xFFBE123C))
            }
            item { SectionTitle("GENERAL & SYSTEM") }
            item {
                SettingRow(Icons.Filled.Call, Color(0xFF059669), iconBg(Color(0xFFD1FAE5), Color(0xFF059669)),
                    "Dialer & Default Permission", "Set as Default Dialer · " + if (activity.isDefaultDialer()) "CallerX is default" else "Not default dialer", onPermissions)
            }
            item {
                SettingRow(Icons.Filled.Block, Color(0xFFDC2626), iconBg(Color(0xFFFEE2E2), Color(0xFFDC2626)),
                    "Blocked numbers", "Calls are rejected, messages arrive silently", onBlocked,
                    badge = if (blockedCount > 0) "$blockedCount" else null)
            }
            item {
                SettingRow(Icons.Filled.PowerSettingsNew, Color(0xFFD97706), iconBg(Color(0xFFFEF3C7), Color(0xFFD97706)),
                    "Power Mode", "$powerMode · " + if (powerMode == "Unrestricted") "Best for background SIP" else "Battery conscious", onPower)
            }
            item {
                SettingRow(Icons.Filled.Palette, Color(0xFF7C3AED), iconBg(Color(0xFFEDE9FE), Color(0xFF7C3AED)),
                    "Theme color", themeName, onTheme, swatch = accentColor)
            }
            item {
                SettingRow(Icons.Filled.DarkMode, Color(0xFF475569), iconBg(Color(0xFFE2E8F0), Color(0xFF475569)),
                    "Dark Mode", if (dark) "On · Dark theme active" else "Off · Clean Light theme active", {},
                    toggle = true, checked = dark, onToggle = { onDark(it) })
            }
            item {
                SettingRow(Icons.Filled.Download, Color(0xFF0891B2), iconBg(Color(0xFFCFFAFE), Color(0xFF0891B2)),
                    "App Update", "Check for a new version", onUpdate)
            }
        }
    }
}
@Composable private fun SettingRow(
    icon: ImageVector,
    iconColor: Color,
    iconBg: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    toggle: Boolean = false,
    checked: Boolean = false,
    onToggle: ((Boolean) -> Unit)? = null,
    badge: String? = null,
    badgeBg: Color = Color(0xFFF1F5F9),
    badgeFg: Color = Color(0xFF475569),
    swatch: Color? = null,
    numberBadge: String? = null
) {
    Row(
        Modifier.fillMaxWidth().clickable { if (toggle) onToggle?.invoke(!checked) else onClick() }.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = iconColor, modifier = Modifier.size(20.dp))
            if (numberBadge != null) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(iconColor).align(Alignment.BottomEnd), contentAlignment = Alignment.Center) {
                    Text(numberBadge, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(6.dp), color = badgeBg) {
                        Text(badge, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = badgeFg, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            if (subtitle.isNotBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (swatch != null) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(swatch).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        if (toggle) Switch(checked = checked, onCheckedChange = { onToggle?.invoke(it) })
        else Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ContactDetailScreen(person: Person, onBack: () -> Unit, onCall: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("Contact details", onBack)
        LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Spacer(Modifier.height(24.dp)); Avatar(person.initials, MaterialTheme.colorScheme.primary, 88); Spacer(Modifier.height(12.dp)); Text(person.name, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text(person.number, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(18.dp)); Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { FilledTonalButton(onClick = onCall) { Icon(Icons.Filled.Call, null); Spacer(Modifier.width(6.dp)); Text("Call") }; FilledTonalButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.Message, null); Spacer(Modifier.width(6.dp)); Text("Message") } }; Spacer(Modifier.height(20.dp)) }
            item { Card(Modifier.fillMaxWidth().padding(horizontal = 18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(16.dp)) { Text("Caller ID details", fontWeight = FontWeight.Bold); Detail("Mobile", person.number); Detail("Carrier", "Bangladesh Mobile Network"); Detail("Location", "Bangladesh"); Detail("Spam score", if (person.spam) "92%" else "Low") } } }
            item { SectionTitle("TAGS") }; item { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Friend", "Family", "Work", "+ Add Tag").forEach { AssistChip(onClick = {}, label = { Text(it) }) } } }
            item { SectionTitle("NOTES") }; item { OutlinedTextField("", {}, Modifier.fillMaxWidth().padding(horizontal = 18.dp), placeholder = { Text("Add a note about this contact…") }, minLines = 3) }
            item { TextButton(onClick = {}) { Icon(Icons.Filled.Report, null, tint = Color.Red); Text("Report or block number", color = Color.Red) } }
        }
    }
}
@Composable private fun Detail(key: String, value: String) { Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(key, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp); Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium) } }

@Composable private fun DialerScreen(number: String, onNumber: (String) -> Unit, onBack: () -> Unit, onCall: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar("Keypad", onBack); Spacer(Modifier.weight(1f)); Text(number.ifBlank { "Enter a number" }, fontSize = 27.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(18.dp))
        val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")
        keys.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { row.forEach { (digit, letters) -> FilledTonalButton(onClick = { onNumber(number + digit) }, modifier = Modifier.size(82.dp), shape = CircleShape) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(digit, fontSize = 23.sp); Text(letters, fontSize = 9.sp) } } } }; Spacer(Modifier.height(5.dp)) }
        Spacer(Modifier.height(15.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Button(onClick = onCall, shape = CircleShape, modifier = Modifier.size(62.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E9C67))) { Icon(Icons.Filled.Call, "Call") }; Spacer(Modifier.width(23.dp)); IconButton(onClick = { if (number.isNotEmpty()) onNumber(number.dropLast(1)) }) { Icon(Icons.Filled.Backspace, "Delete") } }; Spacer(Modifier.weight(1f))
    }
}

@Composable private fun NativeCallScreen(
    name: String, number: String, state: String, source: String, dark: Boolean, muted: Boolean, speaker: Boolean,
    onTheme: (Boolean) -> Unit, onMute: () -> Unit, onSpeaker: () -> Unit, onHold: (Boolean) -> Unit,
    onAnswer: () -> Unit, onDecline: () -> Unit, onEnd: () -> Unit
) {
    var hold by remember { mutableStateOf(false) }
    val background = if (dark) Color(0xFF071426) else Color(0xFFF9FAFB)
    val primaryText = if (dark) Color(0xFFF8FAFC) else Color(0xFF111827)
    val secondaryText = if (dark) Color(0xFFA6B2C2) else Color(0xFF64748B)
    val sourceColor = when { source.startsWith("SIM 1") -> Color(0xFF2563EB); source.startsWith("SIM 2") -> Color(0xFF0F9D8A); else -> Color(0xFF5B5BD6) }
    val incoming = state.startsWith("Incoming", ignoreCase = true)
    val transition = rememberInfiniteTransition()
    val pulse by transition.animateFloat(initialValue = 1f, targetValue = 1.08f, animationSpec = infiniteRepeatable(animation = tween(2200, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse))
    Box(Modifier.fillMaxSize().background(background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Surface(shape = RoundedCornerShape(50), color = sourceColor.copy(alpha = if (dark) .22f else .09f), border = androidx.compose.foundation.BorderStroke(1.dp, sourceColor.copy(alpha = .25f))) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (source.startsWith("SIP")) Icons.Filled.GraphicEq else Icons.Filled.SimCard, null, tint = sourceColor, modifier = Modifier.size(15.dp))
                        Text(source.uppercase(), color = if (dark) Color(0xFFD7D9FF) else sourceColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .7.sp)
                    }
                }
                IconButton(onClick = { onTheme(!dark) }, modifier = Modifier.align(Alignment.CenterEnd).size(36.dp)) {
                    Icon(if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode, "Toggle call screen theme", tint = secondaryText, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(30.dp))
            Text(name.ifBlank { "Unknown caller" }, color = primaryText, fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(number, color = secondaryText, fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = .4.sp, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (incoming) Color(0xFFF59E0B) else Color(0xFF22A06B)))
                Text(state.ifBlank { "Connecting…" }, color = secondaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(224.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(210.dp).graphicsLayer { scaleX = pulse; scaleY = pulse; alpha = .22f }.clip(CircleShape).background(sourceColor.copy(alpha = .15f)))
                Box(Modifier.size(166.dp).graphicsLayer { scaleX = 2f - pulse; scaleY = 2f - pulse; alpha = .36f }.clip(CircleShape).background(sourceColor.copy(alpha = .12f)))
                Box(Modifier.size(106.dp).clip(CircleShape).background(sourceColor), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Call, "Call", tint = Color.White, modifier = Modifier.size(42.dp).graphicsLayer { rotationZ = if (incoming) -28f else 0f })
                }
            }
            Spacer(Modifier.weight(1f))
            if (incoming) {
                Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(onClick = onDecline, modifier = Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFDC3F45), contentColor = Color.White)) { Icon(Icons.Filled.CallEnd, "Decline", modifier = Modifier.size(29.dp)) }
                        Text("Decline", color = primaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(onClick = onAnswer, modifier = Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF1EAA62), contentColor = Color.White)) { Icon(Icons.Filled.Call, "Answer", modifier = Modifier.size(29.dp)) }
                        Text("Answer", color = primaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CallControl(Icons.Filled.MicOff, "Mute", muted, dark, secondaryText, onMute)
                    CallControl(Icons.Filled.VolumeUp, "Speaker", speaker, dark, secondaryText, onSpeaker)
                    CallControl(Icons.Filled.Pause, if (hold) "Resume" else "Hold", hold, dark, secondaryText) { hold = !hold; onHold(hold) }
                }
                Spacer(Modifier.height(25.dp))
                FilledIconButton(onClick = onEnd, modifier = Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFDC3F45), contentColor = Color.White)) { Icon(Icons.Filled.CallEnd, "End call", modifier = Modifier.size(30.dp)) }
                Text("End call", color = primaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp, bottom = 18.dp))
            }
        }
    }
}

@Composable private fun CallControl(icon: ImageVector, label: String, selected: Boolean, dark: Boolean, secondaryText: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = onClick, colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (selected) Color(0xFF2864DC) else if (dark) Color(0xFF1E2B3D) else Color(0xFFE9EDF3), contentColor = if (selected) Color.White else if (dark) Color.White else Color(0xFF334155))) { Icon(icon, label) }
        Text(label, color = secondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
    }
}
@Composable private fun SummaryScreen(name: String, number: String, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp)); Spacer(Modifier.height(14.dp)); Text("Call ended", fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(name, modifier = Modifier.padding(top = 8.dp)); Text(number, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(22.dp)); Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(18.dp)) { Text("Call notes", fontWeight = FontWeight.Bold); Text("Add a note about this call", modifier = Modifier.padding(top = 7.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } }; Spacer(Modifier.height(20.dp)); Button(onClick = onDone) { Text("Done") }
    }
}
@Composable private fun ProfileScreen(onBack: () -> Unit, onTheme: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("callerx", Context.MODE_PRIVATE) }
    var name by remember { mutableStateOf(prefs.getString("profile_name", "Md Rean Islam") ?: "Md Rean Islam") }
    var org by remember { mutableStateOf(prefs.getString("profile_org", "RMF Micro Finance") ?: "RMF Micro Finance") }
    var email by remember { mutableStateOf(prefs.getString("profile_email", "rean.islam@rmfmicrofinance.com") ?: "rean.islam@rmfmicrofinance.com") }
    var phone by remember { mutableStateOf(prefs.getString("profile_phone", "+8809649997742") ?: "+8809649997742") }
    var autoBackup by remember { mutableStateOf(prefs.getBoolean("auto_backup", true)) }
    var lastSync by remember { mutableStateOf(prefs.getString("last_sync", "Not backed up yet") ?: "Not backed up yet") }
    var backingUp by remember { mutableStateOf(false) }
    val initials = name.trim().split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "C" }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("My Profile & Account", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name.ifBlank { "Md Rean Islam" }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(org, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text(phone, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Full Name") }, singleLine = true)
                    OutlinedTextField(org, { org = it }, Modifier.fillMaxWidth(), label = { Text("Organization / Caller ID") }, singleLine = true)
                    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email Address") }, singleLine = true)
                    OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth(), label = { Text("Phone Number") }, singleLine = true)
                    Button(onClick = {
                        prefs.edit().putString("profile_name", name.trim()).putString("profile_org", org.trim()).putString("profile_email", email.trim()).putString("profile_phone", phone.trim()).apply()
                        android.widget.Toast.makeText(context, "Profile updated", android.widget.Toast.LENGTH_SHORT).show()
                        onBack()
                    }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) { Text("Save Profile Details") }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Auto Backup & Restore", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Last synced: $lastSync", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Switch(checked = autoBackup, onCheckedChange = { autoBackup = it; prefs.edit().putBoolean("auto_backup", it).apply() })
                        }
                        OutlinedButton(onClick = {
                            backingUp = true
                            Thread {
                                val ok = backupNow(context)
                                (context as? MainActivity)?.runOnUiThread {
                                    backingUp = false
                                    if (ok) {
                                        lastSync = "Just now"
                                        prefs.edit().putString("last_sync", "Just now").apply()
                                        android.widget.Toast.makeText(context, "Call logs & contacts backed up", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.widget.Toast.makeText(context, "Backup failed", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }.start()
                        }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), enabled = !backingUp) {
                            Text(if (backingUp) "Backing up..." else "Backup Call Logs & Contacts Now")
                        }
                    }
                }
            }
            item {
                SettingRow(Icons.Filled.Palette, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = .11f), "Theme", "Change app colors", onTheme)
                SettingRow(Icons.Filled.Settings, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = .11f), "Settings", "", onSettings)
            }
        }
    }
}
@Composable private fun ThemeScreen(selected: Int, onSelect: (Int) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("Theme color", onBack)
        Text("Choose an accent color for active toggles, buttons, and navigation highlights.", Modifier.padding(horizontal = 18.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(ThemePresets.size) { index ->
                val preset = ThemePresets[index]
                val isSelected = selected == index
                Card(
                    onClick = { onSelect(index) },
                    colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = .08f) else MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(preset.color))
                        Spacer(Modifier.width(12.dp))
                        Text(preset.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (isSelected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// New Settings sub-screens (from the user's HTML design)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AudioScreen(activity: MainActivity, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("callerx", Context.MODE_PRIVATE) }
    var codecs by remember { mutableStateOf(prefs.getStringSet("audio_codecs", setOf("opus", "G722", "PCMU")) ?: setOf("opus", "G722", "PCMU")) }
    var echo by remember { mutableStateOf(prefs.getBoolean("audio_echo", true)) }
    var noise by remember { mutableStateOf(prefs.getBoolean("audio_noise", true)) }
    var micGain by remember { mutableStateOf(prefs.getFloat("audio_mic_gain", 2f)) }

    fun saveCodecs(next: Set<String>) {
        codecs = next
        prefs.edit().putStringSet("audio_codecs", next).apply()
        activity.applyAudioSettings()
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("Audio & Codec", onBack)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 18.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("ENABLED VOIP CODECS (TAP TO TOGGLE)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp) }
            items(CodecPresets.size) { idx ->
                val c = CodecPresets[idx]
                val enabled = codecs.contains(c.mime)
                Card(
                    onClick = {
                        val next = codecs.toMutableSet()
                        if (enabled) next.remove(c.mime) else next.add(c.mime)
                        if (next.isEmpty()) { activity.toast("At least one codec must stay enabled"); return@Card }
                        saveCodecs(next)
                    },
                    colors = CardDefaults.cardColors(containerColor = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = .08f) else MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(c.desc, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Surface(shape = RoundedCornerShape(50), color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
                            Text(if (enabled) "Enabled" else "Off", color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Acoustic Echo Cancellation", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Prevents voice feedback on speakerphone", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Switch(checked = echo, onCheckedChange = { echo = it; prefs.edit().putBoolean("audio_echo", it).apply(); activity.applyAudioSettings() })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("AI Noise Suppression", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Reduces background traffic & fan noise", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Switch(checked = noise, onCheckedChange = { noise = it; prefs.edit().putBoolean("audio_noise", it).apply(); activity.applyAudioSettings() })
                        }
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Microphone Gain Boost", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text((if (micGain > 0) "+" else "") + micGain.toInt().toString() + " dB", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            }
                            Slider(value = micGain, onValueChange = { micGain = it }, onValueChangeFinished = { prefs.edit().putFloat("audio_mic_gain", micGain).apply(); activity.applyAudioSettings() }, valueRange = -5f..10f, steps = 14, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun RecordingsScreen(activity: MainActivity, onBack: () -> Unit) {
    var files by remember { mutableStateOf(activity.recordingFiles()) }
    var playingPath by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    DisposableEffect(Unit) {
        onDispose { try { player?.release() } catch (_: Exception) { } }
    }
    fun playFile(file: java.io.File) {
        try {
            if (playingPath == file.absolutePath) {
                try { player?.stop(); player?.release() } catch (_: Exception) { }
                player = null; playingPath = null; return
            }
            try { player?.release() } catch (_: Exception) { }
            val mp = android.media.MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.start()
            mp.setOnCompletionListener { playingPath = null; try { mp.release() } catch (_: Exception) { }; player = null }
            player = mp
            playingPath = file.absolutePath
        } catch (_: Exception) {
            playingPath = null
            activity.toast("Could not play this recording")
        }
    }
    fun fmtDuration(ms: Long): String {
        val s = (ms / 1000).toInt()
        return String.format("%02d:%02d", s / 60, s % 60)
    }
    fun fmtDate(ts: Long): String {
        return java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(ts))
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("Call Recordings", onBack)
        if (files.isEmpty()) {
            Column(Modifier.weight(1f).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.FiberManualRecord, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                Text("No recordings yet", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
                Text("Turn on Auto Call Recording and your SIP calls will be saved here.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(files) { file ->
                    val duration = recordingDurationMs(file)
                    val isPlaying = playingPath == file.absolutePath
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("SIP Call Recording", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(fmtDate(file.lastModified()) + " · " + fmtDuration(duration), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            FilledIconButton(onClick = { playFile(file) }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White)) {
                                Icon(if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow, if (isPlaying) "Stop" else "Play")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun PermissionRow(name: String, desc: String, granted: Boolean, onRequest: () -> Unit) {
    Card(onClick = onRequest, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(desc, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Text(if (granted) "Allowed" else "Denied", color = if (granted) Color(0xFF059669) else Color(0xFFDC2626), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        }
    }
}

@Composable private fun PermissionsScreen(activity: MainActivity, onBack: () -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE")
    val resumeTick = activity.resumeTick
    val isDefault = activity.isDefaultDialer()
    val contactsOk = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    val phoneOk = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    val micOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val notifOk = if (android.os.Build.VERSION.SDK_INT >= 33) ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true
    val overlayOk = android.provider.Settings.canDrawOverlays(context)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("Dialer & Permissions", onBack)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 18.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = if (isDefault) Color(0xFFECFDF5) else MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(if (isDefault) Color(0xFF059669) else MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Check, null, tint = Color.White)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (isDefault) "CallerX is Default Dialer" else "CallerX is not the default dialer", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("Handles SIM 1, SIM 2 & SIP calls seamlessly", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                        }
                        Button(onClick = { activity.requestDefaultDialer() }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))) {
                            Text("Verify / Set as Default Phone App")
                        }
                    }
                }
            }
            item { Text("SYSTEM PERMISSIONS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp) }
            item { PermissionRow("Contacts", "Match caller names & numbers", contactsOk) { activity.requestPermission(Manifest.permission.READ_CONTACTS) } }
            item { PermissionRow("Phone & Call Logs", "Manage SIM 1, SIM 2 & history", phoneOk) { activity.requestPermission(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG) } }
            item { PermissionRow("Microphone", "Required for VoIP & call audio", micOk) { activity.requestPermission(Manifest.permission.RECORD_AUDIO) } }
            item {
                PermissionRow("Notifications", "Incoming call alerts & missed calls", notifOk) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) activity.requestPermission(Manifest.permission.POST_NOTIFICATIONS)
                    else activity.toast("Notifications are always allowed on this Android version")
                }
            }
            item { PermissionRow("Display Over Other Apps", "Show incoming call banner on lockscreen", overlayOk) { activity.openOverlaySettings() } }
        }
    }
}

@Composable private fun BlockedScreen(activity: MainActivity, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("callerx", Context.MODE_PRIVATE) }
    var input by remember { mutableStateOf("") }
    var blocked by remember { mutableStateOf(loadBlocked(prefs)) }
    fun saveBlocked(next: List<Pair<String, String>>) {
        blocked = next
        prefs.edit().putStringSet("blocked_numbers", next.map { "${it.first}|${it.second}" }.toSet()).apply()
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("Blocked Numbers", onBack)
        Column(Modifier.weight(1f).padding(18.dp)) {
            Text("Blocked callers cannot ring your SIM or SIP lines, and their SMS messages arrive silently.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("+8801XXXXXXXXX") }, singleLine = true)
                Button(onClick = {
                    val num = input.trim()
                    if (num.isEmpty()) { activity.toast("Enter a phone number to block"); return@Button }
                    if (blocked.any { it.first == num }) { activity.toast("Already blocked"); return@Button }
                    saveBlocked(listOf(num to "Manually Blocked") + blocked)
                    input = ""
                    activity.toast("Blocked $num")
                }, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48))) { Text("Block") }
            }
            Spacer(Modifier.height(16.dp))
            if (blocked.isEmpty()) {
                Text("No blocked numbers yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(blocked) { entry ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.first, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(entry.second, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                }
                                TextButton(onClick = {
                                    saveBlocked(blocked.filter { it != entry })
                                    activity.toast("Unblocked ${entry.first}")
                                }) { Text("Unblock") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun PowerScreen(activity: MainActivity, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("callerx", Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(prefs.getString("power_mode", "Unrestricted") ?: "Unrestricted") }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("Power Mode", onBack)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 18.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(PowerModes.size) { idx ->
                val m = PowerModes[idx]
                val isSelected = mode == m.first
                Card(
                    onClick = {
                        mode = m.first
                        prefs.edit().putString("power_mode", m.first).apply()
                        activity.toast("Power Mode: ${m.first}")
                    },
                    colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = .08f) else MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(m.second, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(m.third, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        RadioButton(selected = isSelected, onClick = {
                            mode = m.first
                            prefs.edit().putString("power_mode", m.first).apply()
                            activity.toast("Power Mode: ${m.first}")
                        })
                    }
                }
            }
        }
    }
}

@Composable private fun UpdateScreen(activity: MainActivity, onBack: () -> Unit) {
    val context = LocalContext.current
    var checking by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var latestTag by remember { mutableStateOf<String?>(null) }
    var latestUrl by remember { mutableStateOf<String?>(null) }
    var latestNotes by remember { mutableStateOf("") }
    val pkg = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
    val versionName = pkg?.versionName ?: "?"
    val versionCode = if (pkg != null && android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg?.versionCode?.toLong() ?: 0L
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("App Update", onBack)
        Column(Modifier.weight(1f).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text("C", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Text("CallerX VoIP & Dialer", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp))
            Text("Installed Version: v$versionName (Build $versionCode)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(statusText ?: "You are up to date!", color = if (latestTag != null && isNewerVersion(latestTag!!, versionName)) MaterialTheme.colorScheme.primary else Color(0xFF059669), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(if (latestNotes.isNotBlank()) latestNotes.take(600) else "• Enhanced Dual-SIM & SIP call badges" + "\n" + "• Low-latency Opus HD audio codec support" + "\n" + "• Automatic call recording & cloud backup", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            if (latestTag != null && isNewerVersion(latestTag!!, versionName) && latestUrl != null) {
                Button(onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(latestUrl))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (_: Exception) { activity.toast("Could not open the download page") }
                }, Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Download ${latestTag}") }
            }
            Button(onClick = {
                checking = true
                statusText = null
                Thread {
                    val result = fetchLatestRelease()
                    activity.runOnUiThread {
                        checking = false
                        if (result == null) {
                            statusText = "Could not check for updates. Check your connection and try again."
                            activity.toast("Update check failed")
                        } else {
                            latestTag = result.first
                            latestUrl = result.second
                            latestNotes = result.third
                            val newer = isNewerVersion(result.first, versionName)
                            statusText = if (newer) "Update available: ${result.first}" else "Latest version ${result.first} is installed!"
                            activity.toast(if (newer) "Update available: ${result.first}" else "CallerX $versionName is the latest version")
                        }
                    }
                }.start()
            }, Modifier.fillMaxWidth().padding(top = 20.dp).height(52.dp), shape = RoundedCornerShape(16.dp), enabled = !checking) {
                Text(if (checking) "Checking server..." else "Check for Updates")
            }
        }
    }
}

// --- helpers for the new screens ---

private fun backupNow(context: Context): Boolean {
    return try {
        val contacts = loadContacts(context)
        val calls = loadCallLog(context)
        val arr = org.json.JSONArray()
        contacts.forEach { p -> arr.put(org.json.JSONObject().put("type", "contact").put("name", p.name).put("number", p.number)) }
        calls.forEach { p -> arr.put(org.json.JSONObject().put("type", "call").put("name", p.name).put("number", p.number).put("time", p.time).put("source", p.source)) }
        val dir = java.io.File(context.filesDir, "backup").apply { mkdirs() }
        val file = java.io.File(dir, "callerx-backup-${System.currentTimeMillis()}.json")
        file.writeText(arr.toString(2))
        true
    } catch (_: Exception) { false }
}

private fun loadBlocked(prefs: android.content.SharedPreferences): List<Pair<String, String>> {
    val set = prefs.getStringSet("blocked_numbers", emptySet()) ?: emptySet()
    return set.map { entry ->
        val parts = entry.split("|", limit = 2)
        (parts.getOrNull(0) ?: "") to (parts.getOrNull(1) ?: "Manually Blocked")
    }.filter { it.first.isNotBlank() }.sortedBy { it.first }
}

private fun recordingDurationMs(file: java.io.File): Long {
    return try {
        val mmr = android.media.MediaMetadataRetriever()
        mmr.setDataSource(file.absolutePath)
        val d = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        mmr.release()
        d
    } catch (_: Exception) { 0L }
}

private fun fetchLatestRelease(): Triple<String, String, String>? {
    return try {
        val url = java.net.URL("https://api.github.com/repos/mdreanislam01-ai/REAN-SIP/releases/latest")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "CallerX")
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val obj = org.json.JSONObject(text)
        Triple(obj.optString("tag_name", ""), obj.optString("html_url", ""), obj.optString("body", ""))
    } catch (_: Exception) { null }
}

private fun isNewerVersion(latest: String, current: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("callerx-v").removePrefix("callerx-").removePrefix("v").split(".").mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
    val l = parts(latest)
    val c = parts(current)
    for (i in 0 until maxOf(l.size, c.size)) {
        val lv = l.getOrNull(i) ?: 0
        val cv = c.getOrNull(i) ?: 0
        if (lv != cv) return lv > cv
    }
    return false
}
