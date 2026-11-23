package com.callerx.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.view.View
import android.view.WindowManager
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import org.linphone.core.*

private val Palette = listOf(
    Color(0xFF087DB8), Color(0xFF16845B), Color(0xFF7053B5), Color(0xFFD47622),
    Color(0xFFC34F86), Color(0xFF455FC2), Color(0xFF168C8C), Color(0xFFCA4545), Color(0xFF68717D)
)
private val PaletteNames = listOf("Blue", "Green", "Purple", "Orange", "Pink", "Indigo", "Teal", "Red", "Gray")
data class Person(val name: String, val number: String, val initials: String, val verified: Boolean = false, val spam: Boolean = false, val time: String = "", val callType: Int = 0, val source: String = "", val dur: Long = 0, val ts: Long = 0L)
data class SipAccount(val name: String, val user: String, val pass: String, val domain: String, val port: String, val transport: String, val proxy: String, val displayName: String, val autoBoot: Boolean, val enabled: Boolean, val isDefault: Boolean)

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
    context.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.PHONE_ACCOUNT_ID, CallLog.Calls.DURATION), null, null, CallLog.Calls.DATE + " DESC")?.use { c ->
        while (c.moveToNext()) {
            val name = c.getString(0).orEmpty(); val number = c.getString(1).orEmpty(); val date = c.getLong(2); val accountId = c.getString(4).orEmpty(); val dur = c.getLong(5)
            val source = subSlots[accountId]?.let { "SIM $it" } ?: if (accountId.isNotBlank() && accountId.toIntOrNull() == null) "SIP" else "SIM 1"
            result.add(Person(name.ifBlank { number }, number, name.firstOrNull()?.toString() ?: "?", time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(date)), callType = c.getInt(3), source = source, dur = dur, ts = date))
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
private fun fmtDur(sec: Long): String {
    if (sec <= 0) return ""
    val m = sec / 60; val s = sec % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}

/* ---------- SIP calls are kept in our own history so they survive a cleared keypad ---------- */
private fun loadSipLog(context: Context): List<Person> {
    return try {
        val arr = JSONArray(context.getSharedPreferences("callerx", Context.MODE_PRIVATE).getString("sip_log", "[]") ?: "[]")
        val rows = mutableListOf<Person>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val number = o.optString("number"); if (number.isBlank()) continue
            val name = o.optString("name").ifBlank { number }
            val ts = o.optLong("ts", 0L)
            rows.add(Person(name, number, name.firstOrNull()?.toString() ?: "?",
                time = if (ts > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts)) else "",
                callType = o.optInt("type", CallLog.Calls.OUTGOING_TYPE), source = "SIP", dur = o.optLong("dur", 0L), ts = ts))
        }
        rows
    } catch (_: Exception) { emptyList() }
}

/* Merge the system call log with our SIP history, newest first, without duplicate rows. */
private fun mergeCallLogs(system: List<Person>, sip: List<Person>): List<Person> {
    val combined = mutableListOf<Person>()
    combined.addAll(system)
    sip.forEach { s ->
        val dup = system.any { digitsOnly(it.number) == digitsOnly(s.number) && it.ts > 0 && s.ts > 0 && kotlin.math.abs(it.ts - s.ts) < 8000L }
        if (!dup) combined.add(s)
    }
    return combined.sortedByDescending { it.ts }
}

/* ---------- Smooth, forgiving search matching ---------- */
private val T9_MAP = mapOf(
    'a' to '2', 'b' to '2', 'c' to '2', 'd' to '3', 'e' to '3', 'f' to '3',
    'g' to '4', 'h' to '4', 'i' to '4', 'j' to '5', 'k' to '5', 'l' to '5',
    'm' to '6', 'n' to '6', 'o' to '6', 'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
    't' to '8', 'u' to '8', 'v' to '8', 'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9'
)
private fun digitsOnly(s: String) = s.filter { it.isDigit() }
private fun toT9(s: String) = buildString { for (ch in s.lowercase()) { val d = T9_MAP[ch]; if (d != null) append(d) else if (ch.isDigit()) append(ch) } }

private fun relDay(ts: Long): String {
    if (ts <= 0L) return ""
    val now = java.util.Calendar.getInstance()
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    val sameYear = cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
    return when {
        sameYear && cal.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR) -> "Today"
        sameYear && cal.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR) - 1 -> "Yesterday"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ts))
    }
}

/* Canonical digits for forgiving number search: +8801712345678, 8801712345678,
   01712345678 and 1712345678 all become the same key, so any of them finds the others. */
private fun canonicalDigits(s: String): String {
    var d = digitsOnly(s)
    if (d.startsWith("00")) d = d.drop(2)
    if (d.startsWith("880")) d = d.drop(3)
    else if (d.startsWith("88") && d.length > 10) d = d.drop(2)
    if (d.startsWith("0")) d = d.drop(1)
    return d
}

private fun nameInitials(name: String): String =
    name.split(" ", ",", ";").filter { it.isNotBlank() }.mapNotNull { it.firstOrNull()?.lowercaseChar() }.joinToString("")

private fun matchesQuery(name: String, number: String, rawQuery: String): Boolean {
    val q = rawQuery.trim()
    if (q.isEmpty()) return true
    val lq = q.lowercase()
    if (name.contains(q, true)) return true
    // multi-word name search: every typed word must appear ("rakib has" -> Rakib Hasan)
    val words = lq.split(" ", ",", ";").filter { it.isNotBlank() }
    if (words.size > 1 && words.all { name.lowercase().contains(it) }) return true
    val digits = digitsOnly(number)
    val qDigits = digitsOnly(q)
    if (qDigits.isNotEmpty()) {
        if (digits.contains(qDigits)) return true
        val canon = canonicalDigits(number)
        val qCanon = canonicalDigits(q)
        if (qCanon.isNotEmpty() && canon.contains(qCanon)) return true
        if (qCanon.length >= 4 && canon.endsWith(qCanon)) return true
        // true T9: typed digits find a contact by name (72446 -> "rahi…")
        if (q.all { it.isDigit() } && q.length >= 2) {
            val nameT9 = toT9(name)
            if (nameT9.isNotEmpty() && nameT9.contains(qDigits)) return true
        }
    }
    if (q.any { it.isLetter() }) {
        val t9 = toT9(q)
        if (t9.isNotEmpty() && digits.contains(t9)) return true           // typed letters -> number digits
        val initials = nameInitials(name)
        val lettersOnly = lq.filter { it.isLetter() }
        if (lettersOnly.length > 1 && initials.contains(lettersOnly)) return true   // "rh" -> Rakib Hasan
    }
    return false
}

/* ---------- SIP accounts + blocked numbers: shared by Activity, engine and boot receiver ---------- */
private fun loadSipAccounts(context: Context): List<SipAccount> {
    return try {
        val arr = JSONArray(context.getSharedPreferences("callerx", Context.MODE_PRIVATE).getString("sip_accounts", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SipAccount(o.optString("name"), o.optString("user"), o.optString("pass"), o.optString("domain"),
                o.optString("port", "5060"), o.optString("transport", "UDP"), o.optString("proxy"), o.optString("displayName"),
                o.optBoolean("autoBoot", true), o.optBoolean("enabled", true), o.optBoolean("default", false))
        }
    } catch (_: Exception) { emptyList() }
}

private fun saveSipAccounts(context: Context, list: List<SipAccount>) {
    val arr = JSONArray()
    list.forEach { a ->
        arr.put(JSONObject().put("name", a.name).put("user", a.user).put("pass", a.pass).put("domain", a.domain)
            .put("port", a.port).put("transport", a.transport).put("proxy", a.proxy).put("displayName", a.displayName)
            .put("autoBoot", a.autoBoot).put("enabled", a.enabled).put("default", a.isDefault))
    }
    context.getSharedPreferences("callerx", Context.MODE_PRIVATE).edit().putString("sip_accounts", arr.toString()).apply()
}

private fun loadBlockedNumbers(context: Context): List<String> {
    return try {
        val arr = JSONArray(context.getSharedPreferences("callerx", Context.MODE_PRIVATE).getString("blocked", "[]") ?: "[]")
        (0 until arr.length()).map { arr.getString(it) }.toMutableList()
    } catch (_: Exception) { mutableListOf() }
}

private fun isBlockedNumber(context: Context, number: String): Boolean {
    val norm = number.filter { it.isDigit() || it == '+' }
    val canon = canonicalDigits(norm)
    return loadBlockedNumbers(context).any {
        val b = it.filter { c -> c.isDigit() }
        it.isNotEmpty() && b.isNotEmpty() && (norm.endsWith(b) || norm == it || (canon.isNotEmpty() && canon == canonicalDigits(it)))
    }
}

object CallBridge { var activity: MainActivity? = null }

class CallNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = CallBridge.activity
        val cell = TelecomCallBridge.currentCall != null
        when (intent.action) {
            "callerx.call.end" -> { if (cell) TelecomCallBridge.currentCall?.disconnect() else SipEngine.endCall() }
            "callerx.call.speaker" -> {
                val on = !(a?.speaker ?: false)
                if (cell) TelecomCallBridge.service?.setSpeakerRoute(on) else SipEngine.setSpeakerOn(on)
            }
            "callerx.call.mute" -> {
                val on = !(a?.muted ?: false)
                if (cell) TelecomCallBridge.service?.setMuted(on) else SipEngine.toggleMute(on)
            }
        }
    }
}

/* Restarts the SIP engine after a reboot / app update so the line is online
   without the user having to open CallerX first. */
class SipBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (loadSipAccounts(context).none { it.enabled }) return
            val i = Intent(context, SipKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        } catch (_: Exception) { }
    }
}

/* The SIP engine lives here, NOT in the Activity. The Activity can be paused,
   minimised or destroyed when the phone is locked — the engine (and therefore
   the microphone, the registration and any ongoing call) keeps running inside
   the foreground service. The Activity only observes state through CallBridge. */
object SipEngine {
    private var core: Core? = null
    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeCall: Call? = null
    private var sipCallIncoming = false
    private var sipCallLogged = true
    private var regFailCount = 0
    private var regRetryPending = false
    private var incomingVibrationOn = false
    private var audioFocusHeld = false
    private var callWakeLock: PowerManager.WakeLock? = null
    private var callWifiLock: android.net.wifi.WifiManager.WifiLock? = null
    /* Mirrors of the Activity UI state, so a recreated Activity (e.g. after the
       system killed it while locked) immediately shows the right call screen. */
    var isRegistered by mutableStateOf(false)
    var lastRegistrationMessage by mutableStateOf("SIP Offline")
    var lastCallState by mutableStateOf("")
    var callActive by mutableStateOf(false)

    private val refreshRunnable = Runnable { try { core?.refreshRegisters() } catch (_: Exception) { } }
    private fun scheduleRefresh(delayMs: Long) {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, delayMs)
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { scheduleRefresh(800) }
        /* onLost intentionally does nothing: refreshing on lost only causes
           register flapping when switching between Wi-Fi and mobile data. */
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { }

    @Synchronized
    fun ensureStarted(context: Context) {
        appContext = context.applicationContext
        if (core != null) return
        try {
            val ctx = context.applicationContext
            val soundsDir = prepareSounds(ctx)
            val c = Factory.instance().createCore(buildCoreConfig(soundsDir), null, ctx)
            core = c
            c.addListener(object : CoreListenerStub() {
                override fun onAccountRegistrationStateChanged(core: Core, account: Account, state: RegistrationState, message: String) {
                    mainHandler.post {
                        val a = CallBridge.activity
                        val isDefault = core.defaultAccount == account
                        if (isDefault || !isRegistered) isRegistered = state == RegistrationState.Ok
                        if (isDefault || state == RegistrationState.Ok) {
                            lastRegistrationMessage = when (state) {
                                RegistrationState.Ok -> "SIP Online"
                                RegistrationState.Progress -> "Registering…"
                                RegistrationState.Failed -> "SIP Registration Failed"
                                else -> "SIP Offline"
                            }
                        }
                        a?.registered = isRegistered
                        a?.registrationMessage = lastRegistrationMessage
                        if (state == RegistrationState.Ok) regFailCount = 0
                        if (state == RegistrationState.Failed && isDefault) {
                            regFailCount++
                            scheduleRegRetry()
                            if (regFailCount >= 3 && a != null) {
                                a.errorMessage = "SIP registration failed: $message — CallerX keeps retrying automatically. If it stays offline, check username, password, server domain, port and transport (UDP/TCP/TLS) with your SIP provider."
                            }
                        }
                    }
                }
                override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
                    mainHandler.post {
                        val a = CallBridge.activity
                        activeCall = if (state == Call.State.End || state == Call.State.Error || state == Call.State.Released) null else call
                        when (state) {
                            Call.State.IncomingReceived -> {
                                val remoteName = call.remoteAddress?.displayName?.takeIf { it.isNotBlank() } ?: "Incoming caller"
                                val remoteNumber = call.remoteAddress?.username ?: call.remoteAddressAsString.orEmpty()
                                if (a != null) {
                                    a.activeCallName = remoteName
                                    a.activeNumber = remoteNumber
                                    a.callConnected = false
                                    a.muted = false; a.speaker = false
                                    a.callStartedAt = 0L; a.recording = false
                                }
                                sipCallIncoming = true; sipCallLogged = false
                                acquireCallLocks()
                                acquireAudioFocus()
                                routeAudio(false)
                                val ctx = appContext
                                if (ctx != null && isBlockedNumber(ctx, remoteNumber)) {
                                    try { call.decline(Reason.Busy) } catch (_: Exception) { }
                                } else startIncomingVibration()
                            }
                            Call.State.OutgoingInit, Call.State.OutgoingProgress -> {
                                if (a != null) { a.callConnected = false; a.muted = false; a.speaker = false; a.callStartedAt = 0L; a.recording = false }
                                sipCallIncoming = false; sipCallLogged = false
                                acquireCallLocks()
                                acquireAudioFocus()
                                routeAudio(a?.speaker ?: false)
                            }
                            Call.State.Connected -> {
                                if (a != null) { a.callConnected = true; if (a.callStartedAt == 0L) a.callStartedAt = System.currentTimeMillis() }
                                stopIncomingVibration()
                            }
                            Call.State.StreamsRunning -> {
                                if (a != null) { a.callConnected = true; if (a.callStartedAt == 0L) a.callStartedAt = System.currentTimeMillis() }
                                stopIncomingVibration()
                                acquireCallLocks()
                                acquireAudioFocus()
                                maybeAutoRecord()
                            }
                            Call.State.End, Call.State.Released, Call.State.Error -> {
                                stopIncomingVibration()
                                if (!sipCallLogged && a != null) {
                                    val dur = if (a.callStartedAt > 0L) ((System.currentTimeMillis() - a.callStartedAt) / 1000L).coerceAtLeast(0L) else 0L
                                    a.logSipCall(a.activeNumber, a.activeCallName, sipCallIncoming, a.callConnected, dur)
                                    sipCallLogged = true
                                }
                                if (a != null) { a.callStartedAt = 0L; a.recording = false; a.callConnected = false }
                                releaseCallLocks()
                                releaseAudioFocus()
                            }
                            else -> { }
                        }
                        lastCallState = when (state) {
                            Call.State.IncomingReceived -> "Incoming SIP call"
                            Call.State.OutgoingInit, Call.State.OutgoingProgress -> "Calling…"
                            Call.State.OutgoingRinging -> "Ringing…"
                            Call.State.OutgoingEarlyMedia -> "Connecting audio…"
                            Call.State.Connected -> "Connected"
                            Call.State.StreamsRunning -> "In call"
                            Call.State.Paused -> "On hold"
                            Call.State.Resuming -> "Resuming…"
                            Call.State.End, Call.State.Released -> "Call ended"
                            Call.State.Error -> "Call failed: $message"
                            else -> lastCallState
                        }
                        a?.callState = lastCallState
                        val active = state == Call.State.IncomingReceived || state == Call.State.OutgoingInit ||
                            state == Call.State.OutgoingProgress || state == Call.State.OutgoingRinging ||
                            state == Call.State.OutgoingEarlyMedia || state == Call.State.Connected ||
                            state == Call.State.StreamsRunning || state == Call.State.Paused || state == Call.State.Resuming
                        callActive = active
                        if (active) showCallNotification() else cancelCallNotification()
                    }
                }
            })
            c.start()
            try { ctx.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(netCallback) } catch (_: Exception) { }
            applyAccounts(ctx)
        } catch (e: Exception) {
            CallBridge.activity?.errorMessage = "VoIP engine couldn't start: ${e.message ?: "unknown error"}"
        }
    }

    fun shutdown() {
        try { appContext?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(netCallback) } catch (_: Exception) { }
        releaseCallLocks()
        releaseAudioFocus()
        try { core?.stop() } catch (_: Exception) { }
        core = null
        activeCall = null
        callActive = false
    }

    fun refreshRegisters() { scheduleRefresh(800) }

    fun startService(context: Context) {
        try {
            val i = Intent(context, SipKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        } catch (_: Exception) { }
    }

    private fun scheduleRegRetry() {
        if (regRetryPending) return
        regRetryPending = true
        mainHandler.postDelayed({
            regRetryPending = false
            if (!isRegistered) { try { core?.refreshRegisters() } catch (_: Exception) { } ; scheduleRegRetry() }
        }, 20000)
    }

    private fun acquireCallLocks() {
        val ctx = appContext ?: return
        try {
            if (callWakeLock == null) {
                val pm = ctx.getSystemService(PowerManager::class.java)
                callWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "callerx:call")
                callWakeLock?.setReferenceCounted(false)
                callWakeLock?.acquire(3600000L)
            }
        } catch (_: Exception) { }
        try {
            if (callWifiLock == null) {
                val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                val wl = wm?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "callerx:wifi")
                callWifiLock = wl
                wl?.setReferenceCounted(false)
                wl?.acquire()
            }
        } catch (_: Exception) { }
    }

    private fun releaseCallLocks() {
        try { callWakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        callWakeLock = null
        try { callWifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        callWifiLock = null
    }

    private fun acquireAudioFocus() {
        val ctx = appContext ?: return
        try {
            val am = ctx.getSystemService(AudioManager::class.java) ?: return
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 26) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setOnAudioFocusChangeListener(audioFocusListener).build()
                audioFocusHeld = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioFocusHeld = am.requestAudioFocus(audioFocusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Exception) { }
    }

    private fun releaseAudioFocus() {
        val ctx = appContext ?: return
        try {
            val am = ctx.getSystemService(AudioManager::class.java) ?: return
            am.mode = AudioManager.MODE_NORMAL
            if (audioFocusHeld) {
                if (Build.VERSION.SDK_INT >= 26) {
                    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setOnAudioFocusChangeListener(audioFocusListener).build()
                    am.abandonAudioFocusRequest(request)
                } else {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(audioFocusListener)
                }
                audioFocusHeld = false
            }
        } catch (_: Exception) { }
    }

    /* Audio routing done properly:
         · Bluetooth / wired headset wins when it is connected (unless the user
           asked for the speaker), and the platform SCO link is started so some
           OEMs do not keep the voice on the earpiece.
         · otherwise speaker or earpiece, matching the on-screen toggle. */
    private fun routeAudio(preferSpeaker: Boolean) {
        val ctx = appContext ?: return
        val c = core ?: return
        try {
            val am = ctx.getSystemService(AudioManager::class.java) ?: return
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            val devices = c.audioDevices
            fun pick(vararg names: String): AudioDevice? =
                devices.firstOrNull { d -> names.any { n -> d.type.name.equals(n, ignoreCase = true) } }
            val headset = pick("Bluetooth", "Headset", "Headphones", "HearingAid")
            val target = if (preferSpeaker) (pick("Speaker") ?: headset)
                else (headset ?: pick("Earpiece") ?: pick("Speaker") ?: devices.firstOrNull())
            if (target != null) {
                c.outputAudioDevice = target
                val isHeadset = target.type.name.equals("Bluetooth", true) ||
                    target.type.name.equals("Headset", true) || target.type.name.equals("Headphones", true)
                if (isHeadset) try { c.inputAudioDevice = target } catch (_: Exception) { }
            }
            val useBluetooth = headset != null && headset.type.name.equals("Bluetooth", true) && !preferSpeaker
            if (Build.VERSION.SDK_INT < 31) {
                try {
                    if (useBluetooth) { am.startBluetoothSco(); am.isBluetoothScoOn = true }
                    else { am.stopBluetoothSco(); am.isBluetoothScoOn = false }
                } catch (_: Exception) { }
            }
            try { am.isSpeakerphoneOn = preferSpeaker && headset == null } catch (_: Exception) { }
        } catch (_: Exception) { }
    }

    fun applyAccounts(context: Context) {
        ensureStarted(context)
        val c = core ?: return
        try {
            c.clearAccounts()
            c.clearAllAuthInfo()
            val list = loadSipAccounts(context).filter { it.enabled }
            var default: Account? = null
            for (a in list) {
                if (a.user.isBlank() || a.domain.isBlank()) continue
                val auth = Factory.instance().createAuthInfo(a.user, null, a.pass, null, null, a.domain)
                val params = c.createAccountParams()
                val identity = Factory.instance().createAddress("sip:${a.user}@${a.domain}") ?: continue
                val transport = a.transport.lowercase().ifBlank { "udp" }
                val port = a.port.trim().ifBlank { if (transport == "tls") "5061" else "5060" }
                val serverStr = when {
                    a.proxy.isNotBlank() -> {
                        val px = a.proxy.trim()
                        if (px.startsWith("sip:")) px else "sip:$px" + if (px.contains(";transport=")) "" else ";transport=$transport"
                    }
                    else -> "sip:${a.domain}:$port;transport=$transport"
                }
                val server = Factory.instance().createAddress(serverStr) ?: continue
                params.setIdentityAddress(identity)
                params.setServerAddress(server)
                params.setRegisterEnabled(a.autoBoot)
                try { params.setExpires(300) } catch (_: Exception) { }
                val account = c.createAccount(params) ?: continue
                c.addAuthInfo(auth)
                c.addAccount(account)
                if (a.isDefault && default == null) default = account
            }
            c.defaultAccount = default ?: c.accountList.firstOrNull()
            isRegistered = false
            regFailCount = 0
            lastRegistrationMessage = if (c.accountList.isNotEmpty()) "Registering…" else "SIP Offline"
            CallBridge.activity?.registered = false
            CallBridge.activity?.registrationMessage = lastRegistrationMessage
            if (c.accountList.isNotEmpty()) startService(context)
        } catch (e: Exception) {
            isRegistered = false
            lastRegistrationMessage = "SIP Offline"
            CallBridge.activity?.registered = false
            CallBridge.activity?.registrationMessage = "SIP Offline"
            CallBridge.activity?.errorMessage = "SIP setup failed: ${e.message ?: "check the account details"}"
        }
    }

    private fun prepareSounds(ctx: Context): File {
        val dir = File(ctx.filesDir, "sounds")
        try {
            if (!dir.exists()) dir.mkdirs()
            for (name in listOf("ring.wav", "ringback.wav")) {
                val out = File(dir, name)
                if (!out.exists() || out.length() < 1024) {
                    ctx.assets.open("sounds/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }
        } catch (_: Exception) { }
        return dir
    }

    private fun buildCoreConfig(soundsDir: File): String? {
        return try {
            val ctx = appContext ?: return null
            val cfg = File(ctx.filesDir, "linphonerc")
            val ring = File(soundsDir, "ring.wav").absolutePath
            val ringback = File(soundsDir, "ringback.wav").absolutePath
            val lines = listOf(
                "[net]",
                "download_bw=0",
                "upload_bw=0",
                "mtu=1300",
                "stun_server=stun.l.google.com:19302",
                "protocols=stun,ice",
                "nat_policy_ref=default_nat_policy",
                "",
                "[default_nat_policy]",
                "protocols=stun,ice",
                "stun_server=stun.l.google.com:19302",
                "",
                "[sound]",
                "ring=$ring",
                "ringback=$ringback",
                "remote_ring=$ring",
                "echocancellation=1",
                "echocancellation_tail_len=250",
                "",
                "[rtp]",
                "audio_rtp_port=7078",
                "video_rtp_port=0",
                "",
                "[video]",
                "enabled=0",
                "",
                "[audio_codec_0]",
                "mime=opus",
                "rate=16000",
                "channels=1",
                "enabled=1",
                "",
                "[audio_codec_1]",
                "mime=G722",
                "rate=8000",
                "channels=1",
                "enabled=1",
                "",
                "[audio_codec_2]",
                "mime=PCMA",
                "rate=8000",
                "channels=1",
                "enabled=1",
                "",
                "[audio_codec_3]",
                "mime=PCMU",
                "rate=8000",
                "channels=1",
                "enabled=1",
                "",
                "[audio_codec_4]",
                "mime=telephone-event",
                "rate=8000",
                "channels=1",
                "enabled=1",
                "",
                "[sip]",
                "guess_hostname=0",
                "use_ipv6=0",
                "inc_timeout=60",
                "register_only_when_network_is_up=1",
                "ping_with_options=1",
                "keepalive_period=15000"
            )
            cfg.writeText(lines.joinToString("\n"))
            cfg.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun startIncomingVibration() {
        if (incomingVibrationOn) return
        val ctx = appContext ?: return
        try {
            val vibrator = ctx.getSystemService(Vibrator::class.java) ?: return
            if (!vibrator.hasVibrator()) return
            val pattern = longArrayOf(0, 800, 600)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(pattern, 0)
            }
            incomingVibrationOn = true
        } catch (_: Exception) { }
    }

    private fun stopIncomingVibration() {
        if (!incomingVibrationOn) return
        try { appContext?.getSystemService(Vibrator::class.java)?.cancel() } catch (_: Exception) { }
        incomingVibrationOn = false
    }

    /* ---------- SIP calls ---------- */
    fun startSipCall(number: String, name: String): Boolean {
        val a = CallBridge.activity
        a?.activeCallSource = "SIP VOIP · HD AUDIO"
        val clean = number.trim().replace(" ", "").replace("-", "")
        if (clean.isEmpty()) { a?.errorMessage = "Enter a phone number or SIP address first."; return false }
        if (a != null && !a.registered) {
            a.errorMessage = "SIP account is not registered yet. Open Settings → SIP Account, add your account and wait until the status shows “SIP Online”, then call again."
            return false
        }
        val c = core ?: run { a?.errorMessage = "VoIP engine is not ready yet. Restart CallerX and try again."; return false }
        return try {
            val domain = c.defaultAccount?.params?.identityAddress?.domain.orEmpty()
            val target = if (clean.startsWith("sip:") || clean.contains("@")) clean else "sip:$clean@$domain"
            val address = c.interpretUrl(target)
            if (address == null) { a?.errorMessage = "“$number” is not a valid SIP address. Dial the number your SIP provider expects (with or without +/00 prefix)."; return false }
            if (a != null) { a.activeCallName = name; a.activeNumber = clean; a.callConnected = false }
            val call = c.inviteAddress(address)
            if (call == null) { a?.errorMessage = "The call could not be started. Check your network connection and SIP account, then try again."; return false }
            activeCall = call
            lastCallState = "Calling…"
            a?.callState = lastCallState
            true
        } catch (e: Exception) { a?.errorMessage = "Call could not start: ${e.message ?: "network unavailable"}"; false }
    }

    fun answerCall() { stopIncomingVibration(); try { core?.currentCall?.let { it.accept() } } catch (e: Exception) { CallBridge.activity?.errorMessage = "Could not answer call: ${e.message}" } }
    fun endCall() { stopIncomingVibration(); try { core?.currentCall?.terminate() ?: core?.terminateAllCalls() } catch (_: Exception) { core?.terminateAllCalls() } }
    fun toggleMute(muted: Boolean) {
        CallBridge.activity?.muted = muted
        if (TelecomCallBridge.currentCall != null) { TelecomCallBridge.service?.setMuted(muted); showCallNotification(); return }
        core?.currentCall?.microphoneMuted = muted
        showCallNotification()
    }
    fun toggleHold(held: Boolean) {
        try {
            if (TelecomCallBridge.currentCall != null) { if (held) TelecomCallBridge.currentCall?.hold() else TelecomCallBridge.currentCall?.unhold() }
            else core?.currentCall?.let { if (held) it.pause() else it.resume() }
        } catch (e: Exception) { CallBridge.activity?.errorMessage = "Could not ${if (held) "hold" else "resume"} call: ${e.message}" }
    }
    fun setSpeakerOn(enabled: Boolean) {
        CallBridge.activity?.speaker = enabled
        if (TelecomCallBridge.currentCall != null) { TelecomCallBridge.service?.setSpeakerRoute(enabled); showCallNotification(); return }
        routeAudio(enabled)
        showCallNotification()
    }
    fun callQuality(): Float = try { core?.currentCall?.currentQuality ?: -1f } catch (_: Exception) { -1f }

    fun toggleCallRecording() {
        val a = CallBridge.activity
        val call = core?.currentCall
        if (call == null) { a?.recording = false; return }
        try {
            if (a?.recording == true) {
                call.stopRecording()
                a.recording = false
                return
            }
            val ctx = appContext ?: return
            val dir = File(ctx.filesDir, "recordings")
            if (!dir.exists()) dir.mkdirs()
            val safe = (a?.activeCallName ?: "call").filter { it.isLetterOrDigit() }.ifBlank { "call" }
            val path = File(dir, "${safe}_${System.currentTimeMillis()}.mkv").absolutePath
            try {
                call.params?.let { p -> p.recordFile = path; call.update(p) }
            } catch (_: Exception) { }
            call.startRecording()
            if (a != null) a.recording = true
        } catch (e: Exception) {
            a?.errorMessage = "Recording failed: ${e.message ?: "unknown error"}"
        }
    }

    private fun maybeAutoRecord() {
        val a = CallBridge.activity ?: return
        if (a.recording) return
        val ctx = appContext ?: return
        if (!ctx.getSharedPreferences("callerx", Context.MODE_PRIVATE).getBoolean("setting:autoRecord", false)) return
        toggleCallRecording()
    }

    /* ---------- Ongoing-call notification (full-screen over the lock screen) ---------- */
    private fun callPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CallNotificationReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun showCallNotification() {
        val ctx = appContext ?: return
        val a = CallBridge.activity
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel("callerx_call_v2") == null) {
                val ch = NotificationChannel("callerx_call_v2", "Ongoing calls", NotificationManager.IMPORTANCE_HIGH)
                ch.setShowBadge(false)
                ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                nm.createNotificationChannel(ch)
            }
            @Suppress("DEPRECATION")
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(ctx, "callerx_call_v2") else Notification.Builder(ctx)
            val title = (a?.activeCallName ?: "").ifBlank { a?.activeNumber ?: "" }.ifBlank { "CallerX call" }
            builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText("${a?.callState ?: lastCallState} · ${a?.activeCallSource ?: "SIP VOIP · HD AUDIO"}")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
            val openIntent = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("callerx_open_call", true)
            val openPending = PendingIntent.getActivity(ctx, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setContentIntent(openPending)
            if (lastCallState == "Incoming SIP call") {
                try { builder.setFullScreenIntent(openPending, true) } catch (_: Exception) { }
            }
            builder.addAction(Notification.Action.Builder(R.mipmap.ic_launcher, if (a?.muted == true) "Unmute" else "Mute", callPendingIntent(ctx, "callerx.call.mute", 11)).build())
            builder.addAction(Notification.Action.Builder(R.mipmap.ic_launcher, if (a?.speaker == true) "Earpiece" else "Speaker", callPendingIntent(ctx, "callerx.call.speaker", 12)).build())
            builder.addAction(Notification.Action.Builder(R.mipmap.ic_launcher, "End call", callPendingIntent(ctx, "callerx.call.end", 13)).build())
            nm.notify(4711, builder.build())
        } catch (_: Exception) { }
    }

    fun cancelCallNotification() {
        try { appContext?.getSystemService(NotificationManager::class.java)?.cancel(4711) } catch (_: Exception) { }
    }
}

class SipKeepAliveService : android.app.Service() {
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel("callerx_sip") == null) {
                nm?.createNotificationChannel(NotificationChannel("callerx_sip", "SIP service", NotificationManager.IMPORTANCE_LOW))
            }
            val notif = Notification.Builder(this, "callerx_sip")
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle("CallerX SIP")
                .setContentText("Keeping your SIP line online for calls")
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(4712, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else if (Build.VERSION.SDK_INT >= 29) startForeground(4712, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(4712, notif)
        } catch (_: Exception) { }
        try { SipEngine.ensureStarted(this) } catch (_: Exception) { }
        return START_STICKY
    }
    override fun onDestroy() {
        try { SipEngine.shutdown() } catch (_: Exception) { }
        super.onDestroy()
    }
}
class MainActivity : ComponentActivity() {
    /* SIP engine state lives in SipEngine (foreground service). These fields are
       the UI mirror that the engine updates through CallBridge. */
    var registered by mutableStateOf(false)
    var registrationMessage by mutableStateOf("SIP Offline")
    var callState by mutableStateOf("")
    var activeNumber by mutableStateOf("")
    var activeCallName by mutableStateOf("")
    var activeCallSource by mutableStateOf("SIP VOIP · HD AUDIO")
    var errorMessage by mutableStateOf("")
    var callConnected by mutableStateOf(false)
    var muted by mutableStateOf(false)
    var speaker by mutableStateOf(false)
    var callStartedAt by mutableStateOf(0L)
    var recording by mutableStateOf(false)

    /* ---------- Cached lists: always loaded on a background thread, never on the UI thread ---------- */
    var contactsCache by mutableStateOf(emptyList<Person>())
    var callsCache by mutableStateOf(emptyList<Person>())
    var smsCache by mutableStateOf(emptyList<Pair<String, String>>())
    var dataLoading by mutableStateOf(false)
    private var refreshJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshData() }
    private val dataObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) { scheduleDataRefresh(350) }
    }
    var dialIntentNumber by mutableStateOf("")
    var callUiRequested by mutableStateOf(false)
    var dialerRoleTick by mutableIntStateOf(0)
    var profileTick by mutableIntStateOf(0)
    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                val out = File(filesDir, "profile_photo.jpg")
                contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                profileTick++
            } catch (e: Exception) { errorMessage = "Could not load the selected photo: ${e.message ?: "unknown error"}" }
        }
    }
    var resumeTick by mutableIntStateOf(0)
    var accountsTick by mutableIntStateOf(0)
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
        refreshData()
    }
    private val defaultDialerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        dialerRoleTick++
        if (!isDefaultDialer()) errorMessage = "Android did not set CallerX as the default dialer. Check Settings → Apps → Default apps → Phone app, then select CallerX. If CallerX is not listed, your Android device may not accept it as a dialer due to its role requirements."
    }

    private fun prefs() = getSharedPreferences("callerx", MODE_PRIVATE)

    /* ---------- Background data loading + live refresh ---------- */
    fun scheduleDataRefresh(delayMs: Long = 0) {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, delayMs)
    }

    fun refreshData() {
        if (refreshJob?.isActive == true) { scheduleDataRefresh(500); return }
        refreshJob = lifecycleScope.launch {
            dataLoading = true
            val data = withContext(Dispatchers.IO) {
                val ctx = this@MainActivity
                Triple(loadContacts(ctx), mergeCallLogs(loadCallLog(ctx), loadSipLog(ctx)), loadSms(ctx))
            }
            contactsCache = data.first
            callsCache = data.second
            smsCache = data.third
            dataLoading = false
        }
    }

    private fun registerDataObservers() {
        try {
            contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, dataObserver)
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, dataObserver)
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, dataObserver)
        } catch (_: Exception) { }
    }

    /* SIP calls are stored in our own history, so a cleared keypad never loses the call. */
    fun logSipCall(number: String, name: String, incoming: Boolean, answered: Boolean, durationSec: Long) {
        val clean = number.filter { it.isDigit() || it == '+' }
        if (clean.isBlank()) return
        try {
            val arr = JSONArray(prefs().getString("sip_log", "[]") ?: "[]")
            val o = JSONObject()
            o.put("number", clean)
            o.put("name", name.ifBlank { clean })
            o.put("ts", System.currentTimeMillis())
            o.put("type", if (!incoming) CallLog.Calls.OUTGOING_TYPE else if (answered) CallLog.Calls.INCOMING_TYPE else CallLog.Calls.MISSED_TYPE)
            o.put("dur", durationSec.coerceAtLeast(0L))
            arr.put(o)
            val trimmed = JSONArray()
            for (i in maxOf(0, arr.length() - 300) until arr.length()) trimmed.put(arr.get(i))
            prefs().edit().putString("sip_log", trimmed.toString()).apply()
            scheduleDataRefresh(80)
        } catch (_: Exception) { }
    }

    /* ---------- SIP reliability: engine runs in the foreground service ---------- */
    fun startKeepAliveService() {
        SipEngine.startService(this)
    }

    fun stopKeepAliveService() {
        try { stopService(Intent(this, SipKeepAliveService::class.java)) } catch (_: Exception) { }
    }

    fun pickProfilePhoto() {
        try { pickPhotoLauncher.launch("image/*") } catch (_: Exception) { errorMessage = "No photo picker is available on this device." }
    }

    fun clearProfilePhoto() {
        try { File(filesDir, "profile_photo.jpg").delete() } catch (_: Exception) { }
        profileTick++
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
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) permissions += Manifest.permission.BLUETOOTH_CONNECT
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) runtimePermissionLauncher.launch(missing.toTypedArray())
    }

    /* Clear the system call history (Google-Dialer style "Clear history"). */
    fun clearCallHistory() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            runtimePermissionLauncher.launch(arrayOf(Manifest.permission.WRITE_CALL_LOG))
            return
        }
        try {
            contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
            errorMessage = "Call history cleared."
        } catch (e: Exception) { errorMessage = "Could not clear call history: ${e.message}" }
        scheduleDataRefresh(80)
    }

    fun isDefaultDialer(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)
        else (getSystemService(TELECOM_SERVICE) as TelecomManager).defaultDialerPackage == packageName
    }

    fun requestDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (!roles.isRoleAvailable(RoleManager.ROLE_DIALER)) { errorMessage = "This Android device does not offer the dialer role to CallerX. Check your phone manufacturer's default-app settings."; return }
            if (roles.isRoleHeld(RoleManager.ROLE_DIALER)) { errorMessage = "CallerX is already your default dialer."; return }
            defaultDialerLauncher.launch(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        } else {
            @Suppress("DEPRECATION")
            defaultDialerLauncher.launch(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName))
        }
    }

    fun openPowerSettings() {
        try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        catch (_: Exception) { try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { errorMessage = "Could not open battery settings." } }
    }

    fun openNotificationSettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (_: Exception) { errorMessage = "Could not open notification settings." }
    }

    fun openSystemContactEdit(number: String) {
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.LOOKUP_KEY), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val lookup = c.getString(0)
                    startActivity(Intent(Intent.ACTION_EDIT, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookup)))
                    return
                }
            }
            errorMessage = "This number is not saved in device contacts, so it can't be edited."
        } catch (e: Exception) { errorMessage = "Could not open the contact editor: ${e.message}" }
    }

    fun openSms(number: String) {
        try { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))) } catch (_: Exception) { errorMessage = "No SMS app is available on this device." }
    }

    /* ---------- SIM info ---------- */
    fun simSlots(): List<Triple<Int, String, String>> {
        return try {
            val subs = getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
            subs.sortedBy { it.simSlotIndex }.map {
                val carrier = try { it.carrierName?.toString().orEmpty() } catch (_: Exception) { "" }
                val line = try { it.number.orEmpty() } catch (_: Exception) { "" }
                Triple(it.simSlotIndex, carrier.ifBlank { "SIM ${it.simSlotIndex + 1}" }, line)
            }
        } catch (_: Exception) { emptyList() }
    }
    fun simShown(slot: Int) = prefs().getBoolean("setting:SIM ${slot + 1}", true)

    /* ---------- Blocked numbers ---------- */
    fun blockedNumbers(): List<String> = loadBlockedNumbers(this)
    fun setBlocked(number: String, blocked: Boolean) {
        val list = blockedNumbers().toMutableList()
        val norm = number.filter { it.isDigit() || it == '+' }
        if (blocked && !list.contains(norm)) list.add(norm)
        if (!blocked) list.remove(norm)
        prefs().edit().putString("blocked", JSONArray(list.toList()).toString()).apply()
        resumeTick++
    }
    fun isBlockedNumber(number: String): Boolean = isBlockedNumber(this, number)

    /* ---------- Call routing defaults ---------- */
    fun routeAvailable(route: String): Boolean = when (route) {
        "SIP" -> accountsList().any { it.enabled }
        "SIM 1" -> simShown(0) && simSlots().any { it.first == 0 }
        "SIM 2" -> simShown(1) && simSlots().any { it.first == 1 }
        else -> false
    }
    fun routeFor(number: String): String? {
        val p = prefs()
        p.getString("route:$number", null)?.takeIf { routeAvailable(it) }?.let { return it }
        if (!p.getBoolean("setting:Ask before each call", false)) {
            p.getString("route:default", null)?.takeIf { routeAvailable(it) }?.let { return it }
        }
        return null
    }
    fun defaultRoute(): String = prefs().getString("route:default", "") ?: ""
    fun setDefaultRoute(number: String?, route: String) {
        val e = prefs().edit().putString("route:default", route)
        if (number != null) e.putString("route:$number", route)
        e.apply()
    }
    fun clearDefaultRoute() { prefs().edit().remove("route:default").apply() }

    /* ---------- SIP accounts store ---------- */
    fun accountsList(): List<SipAccount> = loadSipAccounts(this)
    private fun saveAccounts(list: List<SipAccount>) {
        saveSipAccounts(this, list)
        accountsTick++
    }
    fun upsertAccount(entry: SipAccount, editIndex: Int) {
        val list = accountsList().toMutableList()
        var normalized = entry
        if (editIndex in list.indices) list[editIndex] = normalized else { list.add(normalized); }
        if (normalized.isDefault || list.size == 1) {
            val idx = if (editIndex in list.indices) editIndex else list.size - 1
            for (i in list.indices) list[i] = list[i].copy(isDefault = i == idx)
        }
        saveAccounts(list)
        applyAccounts()
    }
    fun removeAccount(index: Int) {
        val list = accountsList().toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        if (list.isNotEmpty() && list.none { it.isDefault }) list[0] = list[0].copy(isDefault = true)
        saveAccounts(list)
        applyAccounts()
    }
    fun setAccountEnabled(index: Int, enabled: Boolean) {
        val list = accountsList().toMutableList()
        if (index !in list.indices) return
        list[index] = list[index].copy(enabled = enabled)
        saveAccounts(list)
        applyAccounts()
    }
    fun setDefaultAccount(index: Int) {
        val list = accountsList().toMutableList()
        if (index !in list.indices) return
        for (i in list.indices) list[i] = list[i].copy(isDefault = i == index)
        saveAccounts(list)
        applyAccounts()
    }
    fun applyAccounts() {
        SipEngine.applyAccounts(this)
    }

    /* ---------- Cellular calls ---------- */
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

    /* ---------- Lifecycle ---------- */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        /* The status bar must match the app theme colour, not a white strip:
           a white bar with white icons made the clock/battery unreadable.
           CallerXApp repaints this on every screen change. */
        /* Only the modern insets controller is used: the legacy
           systemUiVisibility flags used to fight it and left white icons on a
           white status bar. CallerXApp sets the colours/appearance per screen. */
        window.statusBarColor = 0xFF087DB8.toInt()   // default accent; repainted by CallerXApp
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
        window.navigationBarColor = android.graphics.Color.rgb(234, 240, 252)
        intent?.data?.schemeSpecificPart?.let { dialIntentNumber = it }
        callUiRequested = intent?.getBooleanExtra("callerx_open_call", false) == true
        CallBridge.activity = this
        /* Restore the engine mirror so a recreated Activity (after the system
           killed it while the phone was locked) shows the live call at once. */
        registered = SipEngine.isRegistered
        registrationMessage = SipEngine.lastRegistrationMessage
        if (SipEngine.callActive) {
            callState = SipEngine.lastCallState
            callUiRequested = true
        }
        /* The engine runs in the foreground service, not here: locking the
           phone or destroying this Activity must never stop the mic/call. */
        startKeepAliveService()
        registerDataObservers()
        refreshData()
        setContent { CallerXApp(this) }
    }

    override fun onResume() { super.onResume(); resumeTick++; CallBridge.activity = this; scheduleDataRefresh(150); SipEngine.refreshRegisters() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.schemeSpecificPart?.let { dialIntentNumber = it }
        callUiRequested = intent.getBooleanExtra("callerx_open_call", false)
    }

    override fun onDestroy() {
        /* Deliberately do NOT stop the SIP engine, the call notification or the
           call locks here: an ongoing SIP call must survive this Activity being
           destroyed (lock screen, task swipe, system reclaim). The foreground
           service owns all of that and cleans up when the call ends. */
        try { contentResolver.unregisterContentObserver(dataObserver) } catch (_: Exception) { }
        mainHandler.removeCallbacks(refreshRunnable)
        refreshJob?.cancel()
        if (CallBridge.activity == this) CallBridge.activity = null
        super.onDestroy()
    }

    /* ---------- SIP calls (delegated to SipEngine in the foreground service) ---------- */
    fun startSipCall(number: String, name: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activeNumber = number; activeCallName = name
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return true
        }
        return SipEngine.startSipCall(number, name)
    }

    fun answerCall() { SipEngine.answerCall() }
    fun answerCellCall() { try { TelecomCallBridge.currentCall?.answer(0) } catch (e: Exception) { errorMessage = "Could not answer call: ${e.message}" } }
    fun endCellCall() { try { TelecomCallBridge.currentCall?.disconnect() } catch (e: Exception) { errorMessage = "Could not end call: ${e.message}" } }
    fun endCall() { SipEngine.endCall() }
    fun toggleMute(muted: Boolean) { SipEngine.toggleMute(muted) }
    fun toggleHold(held: Boolean) { SipEngine.toggleHold(held) }
    fun setSpeakerOn(enabled: Boolean) { SipEngine.setSpeakerOn(enabled) }

    /* ---------- Call metrics: duration, quality, network, recording ---------- */
    fun callQuality(): Float = SipEngine.callQuality()

    fun qualityLabel(q: Float): String = when {
        q < 0f -> ""
        q >= 4f -> "Excellent"
        q >= 3f -> "Good"
        q >= 2f -> "Fair"
        else -> "Poor"
    }

    fun networkTypeLabel(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return "Network"
            val net = cm.activeNetwork ?: return "No network"
            val caps = cm.getNetworkCapabilities(net) ?: return "Network"
            when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> cellularGeneration()
                else -> "Network"
            }
        } catch (_: Exception) { "Network" }
    }

    private fun cellularGeneration(): String {
        return try {
            val tm = getSystemService(android.telephony.TelephonyManager::class.java) ?: return "Mobile"
            val type = if (Build.VERSION.SDK_INT >= 30) tm.dataNetworkType else @Suppress("DEPRECATION") tm.networkType
            when (type) {
                android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "5G"
                android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP, android.telephony.TelephonyManager.NETWORK_TYPE_HSPA,
                android.telephony.TelephonyManager.NETWORK_TYPE_UMTS, android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_0,
                android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_A -> "3G"
                android.telephony.TelephonyManager.NETWORK_TYPE_EDGE, android.telephony.TelephonyManager.NETWORK_TYPE_GPRS,
                android.telephony.TelephonyManager.NETWORK_TYPE_CDMA -> "2G"
                else -> "Mobile"
            }
        } catch (_: Exception) { "Mobile" }
    }

    fun toggleCallRecording() { SipEngine.toggleCallRecording() }

    fun recordingCount(): Int = try { File(filesDir, "recordings").listFiles()?.size ?: 0 } catch (_: Exception) { 0 }

    /* ---------- Ongoing-call notification (owned by SipEngine) ---------- */
    fun showCallNotification() { SipEngine.showCallNotification() }

    fun cancelCallNotification() { SipEngine.cancelCallNotification() }
}


/* =============================== Compose UI =============================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallerXApp(activity: MainActivity) {
    var tab by remember { mutableStateOf("Calls") }
    var screen by remember { mutableStateOf("home") }
    var filter by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var selected by remember { mutableStateOf<Person?>(null) }
    // lists come from the activity cache: loaded on a background thread, kept fresh by ContentObservers
    val contacts = activity.contactsCache
    val recentCalls = activity.callsCache
    val messages = activity.smsCache
    var dark by remember { mutableStateOf(false) }
    var accent by remember { mutableIntStateOf(0) }
    var number by remember { mutableStateOf("") }
    var showCallSheet by remember { mutableStateOf(false) }
    var sheetNumber by remember { mutableStateOf("") }
    var sheetName by remember { mutableStateOf("") }
    var sheetDefault by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var showWelcome by remember { mutableStateOf(false) }
    var sipFormIndex by remember { mutableIntStateOf(-2) }
    var showRouteDialog by remember { mutableStateOf(false) }
    var showDialerDialog by remember { mutableStateOf(false) }
    var accountToDelete by remember { mutableIntStateOf(-1) }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("callerx", Context.MODE_PRIVATE) }

    /* ---------- Back button: never leave the app, always step back one level ---------- */
    var lastBackPress by remember { mutableStateOf(0L) }
    fun goHome(to: String = "Calls") { screen = "home"; tab = to; query = "" }
    BackHandler(enabled = true) {
        when {
            // close any open sheet first
            showCallSheet -> showCallSheet = false
            showRouteDialog -> showRouteDialog = false
            showDialerDialog -> showDialerDialog = false
            showError -> { showError = false; activity.errorMessage = "" }
            showWelcome -> { showWelcome = false; prefs.edit().putBoolean("permission-intro-seen", true).apply() }

            // ongoing / ringing call: back keeps the call alive and shows the call log
            screen == "call" -> goHome("Calls")

            // one step back inside each section
            screen == "sipform" -> screen = "sip"
            screen == "sip" || screen == "blocked" || screen == "theme" || screen == "profile" -> screen = "settings"
            screen == "settings" -> goHome("Calls")
            screen == "contact" -> { selected = null; goHome(tab) }
            screen == "dialer" -> goHome("Calls")

            // home: close search, then jump to Calls tab, then minimise (do not exit)
            screen == "home" && query.isNotBlank() -> { query = ""; keyboardController?.hide() }
            screen == "home" && tab != "Calls" -> { tab = "Calls"; query = "" }
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPress < 2000) {
                    activity.moveTaskToBack(true)   // minimise; app stays alive in recents
                } else {
                    lastBackPress = now
                    Toast.makeText(context, "Press back again to minimise CallerX", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // search is debounced so typing never drops frames
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(query) { if (query.isBlank()) debouncedQuery = "" else { delay(120); debouncedQuery = query } }

    LaunchedEffect(activity.resumeTick) {
        dark = prefs.getBoolean("dark", false)
        accent = prefs.getInt("accent", 0)
        showWelcome = !prefs.getBoolean("permission-intro-seen", false)
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
    LaunchedEffect(activity.callState, TelecomCallBridge.state) {
        val ended = activity.callState == "Call ended" || activity.callState.startsWith("Call failed") || TelecomCallBridge.state == "Call ended"
        if (screen == "call" && ended) { delay(1200); screen = "home"; tab = "Calls" }
    }

    val color = Palette[accent.coerceIn(Palette.indices)]
    val scheme = if (dark) darkColorScheme(primary = color, onPrimary = Color.White, secondary = color, background = Color(0xFF101318), surface = Color(0xFF1B2027), surfaceVariant = Color(0xFF232A33), onSurface = Color(0xFFF2F5F8), onSurfaceVariant = Color(0xFF9AA6B2), outlineVariant = Color(0xFF39424C))
    else lightColorScheme(primary = color, onPrimary = Color.White, secondary = color, background = Color(0xFFF6F8FA), surface = Color.White, surfaceVariant = Color(0xFFEDF1F5), onSurface = Color(0xFF141A21), onSurfaceVariant = Color(0xFF5C6873), outlineVariant = Color(0xFFDDE3E9))

    /* Paint the status bar (clock / wifi / battery strip) with the app theme
       colour so it looks like part of the header instead of a white band.
       Icons switch to white over the coloured header and to dark over the
       light call screen, so they are always readable. */
    val view = LocalView.current
    SideEffect {
        val win = (view.context as? android.app.Activity)?.window
        if (win != null) {
            val statusColor = when (screen) {
                "call" -> if (dark) Color(0xFF071426) else Color(0xFFF9FAFB)
                else -> color                                                     // accent header, white icons
            }
            if (Build.VERSION.SDK_INT < 35) win.statusBarColor = statusColor.toArgb()   // ignored (edge-to-edge) on Android 15+
            androidx.core.view.WindowCompat.getInsetsController(win, view).isAppearanceLightStatusBars =
                screen == "call" && !dark
            /* Call screen must show over the lock screen and keep the screen on,
               otherwise locking the phone kills the visible call UI / mic. */
            if (screen == "call") {
                win.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                win.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    fun placeCall(route: String, num: String, name: String) {
        when (route) {
            "SIP" -> {
                activity.activeNumber = num; activity.activeCallName = name
                if (activity.startSipCall(num, name)) { screen = "call"; number = "" }   // keypad is cleared once the call starts
            }
            "SIM 1" -> { activity.startCellCall(num, 0); number = "" }
            "SIM 2" -> { activity.startCellCall(num, 1); number = "" }
        }
    }
    fun requestCall(num: String, name: String) {
        val clean = num.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (clean.isBlank()) { activity.errorMessage = "Enter a phone number first."; return }
        val route = activity.routeFor(clean)
        if (route != null) placeCall(route, clean, name)
        else { sheetNumber = clean; sheetName = name; sheetDefault = false; showCallSheet = true }
    }

    MaterialTheme(colorScheme = scheme) {
        if (screen == "call") {
            NativeCallScreen(activity, dark, onTheme = { dark = it; prefs.edit().putBoolean("dark", it).apply() },
                onAnswer = { if (TelecomCallBridge.currentCall != null) activity.answerCellCall() else activity.answerCall() },
                onDecline = { if (TelecomCallBridge.currentCall != null) activity.endCellCall() else activity.endCall(); screen = "home"; tab = "Calls" },
                onEnd = { if (TelecomCallBridge.currentCall != null) activity.endCellCall() else activity.endCall(); screen = "home"; tab = "Calls" })
        } else if (screen == "sip") {
            SipAccountsScreen(activity, onBack = { screen = "settings" }, onAdd = { sipFormIndex = -1; screen = "sipform" },
                onEdit = { i -> sipFormIndex = i; screen = "sipform" },
                onDelete = { i -> accountToDelete = i },
                onToggle = { i, en -> activity.setAccountEnabled(i, en) },
                onMakeDefault = { i -> activity.setDefaultAccount(i) })
        } else if (screen == "sipform") {
            SipFormScreen(activity, sipFormIndex, onBack = { screen = "sip" },
                onSave = { entry, idx -> activity.upsertAccount(entry, idx); screen = "sip" })
        } else if (screen == "dialer") {
            DialerScreen(number, { number = it }, contacts, onBack = { screen = "home" }, onCall = { requestCall(number, "Dialed number") }, onCallPerson = { p -> requestCall(p.number, p.name) })
        } else if (screen == "contact" && selected != null) {
            ContactDetailScreen(activity, selected!!, recentCalls,
                onBack = { selected = null; screen = "home" },
                onCall = { requestCall(selected!!.number, selected!!.name) },
                onMessage = { activity.openSms(selected!!.number) },
                onEdit = { activity.openSystemContactEdit(selected!!.number) })
        } else if (screen == "profile") {
            ProfileScreen(activity, onBack = { screen = "settings" }, onTheme = { screen = "theme" }, onSettings = { screen = "settings" }, onNotifications = { activity.openNotificationSettings() })
        } else if (screen == "theme") {
            ThemeScreen(accent, { accent = it; prefs.edit().putInt("accent", it).apply() }, dark, { dark = it; prefs.edit().putBoolean("dark", it).apply() }, onBack = { screen = "settings" })
        } else if (screen == "blocked") {
            BlockedScreen(activity, onBack = { screen = "settings" })
        } else if (screen == "settings") {
            SettingsScreen(activity, dark, { dark = it; prefs.edit().putBoolean("dark", it).apply() },
                onSip = { screen = "sip" }, onTheme = { screen = "theme" }, onProfile = { screen = "profile" },
                onBlocked = { screen = "blocked" }, onRoute = { showRouteDialog = true }, onDialer = { showDialerDialog = true },
                onPower = { activity.openPowerSettings() }, onBack = null)
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = { NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("Calls" to Icons.Filled.Call, "Contacts" to Icons.Filled.Contacts, "Messages" to Icons.AutoMirrored.Filled.Message, "Settings" to Icons.Filled.Settings).forEach { (label, icon) ->
                        NavigationBarItem(selected = tab == label, onClick = { tab = label; query = "" }, icon = { Icon(icon, label, modifier = Modifier.size(20.dp)) }, label = { Text(label, fontSize = 9.sp, maxLines = 1) }, alwaysShowLabel = true)
                    }
                } }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (tab != "Settings") {
                        /* Header = CallerX / SIM 1 · SIM 2 · SIP / full-width search bar,
                           with only "Online" on the right (see HomeHeader). */
                        val hits = when (tab) {
                            "Contacts" -> contacts.count { matchesQuery(it.name, it.number, debouncedQuery) }
                            "Messages" -> messages.count { matchesQuery(it.first, it.first, debouncedQuery) || it.second.contains(debouncedQuery, true) }
                            else -> recentCalls.count { matchesQuery(it.name, it.number, debouncedQuery) }
                        }
                        HomeHeader(
                            tab = tab,
                            online = activity.registered,
                            query = query,
                            onQuery = { query = it },
                            focus = searchFocus,
                            onSearchDone = { keyboardController?.hide() },
                            resultText = if (debouncedQuery.isBlank()) "" else "$hits result${if (hits == 1) "" else "s"}",
                            onSip = { screen = "sip" }
                        )
                    }
                    when (tab) {
                        "Calls" -> CallsTab(recentCalls, filter, { filter = it }, debouncedQuery, onAvatar = { p -> selected = p; screen = "contact" }, onCall = { p -> requestCall(p.number, p.name) }, onMessage = { p -> activity.openSms(p.number) }, onClearHistory = { activity.clearCallHistory() }, onKeypad = { screen = "dialer" })
                        "Contacts" -> {
                            val favoriteNumbers = remember(contacts, activity.resumeTick) {
                                contacts.filter { c -> val norm = c.number.filter { it.isDigit() || it == '+' }; prefs.getBoolean("fav:$norm", false) }.map { it.number }.toSet()
                            }
                            ContactsTab(contacts, debouncedQuery, favoriteNumbers, onOpen = { p -> selected = p; screen = "contact" }, onCall = { p -> requestCall(p.number, p.name) }, onMessage = { p -> activity.openSms(p.number) })
                        }
                        "Messages" -> MessagesTab(activity, messages, debouncedQuery)
                        else -> SettingsScreen(activity, dark, { dark = it; prefs.edit().putBoolean("dark", it).apply() },
                            onSip = { screen = "sip" }, onTheme = { screen = "theme" }, onProfile = { screen = "profile" },
                            onBlocked = { screen = "blocked" }, onRoute = { showRouteDialog = true }, onDialer = { showDialerDialog = true },
                            onPower = { activity.openPowerSettings() }, onBack = null)
                    }
                }
            }
        }

        if (showCallSheet) {
            CallSheetDialog(activity, sheetNumber, sheetName, sheetDefault, { sheetDefault = it },
                onDismiss = { showCallSheet = false },
                onChoose = { route ->
                    val setDef = sheetDefault
                    showCallSheet = false
                    if (setDef) activity.setDefaultRoute(sheetNumber, route)
                    placeCall(route, sheetNumber, sheetName)
                })
        }
        if (showRouteDialog) {
            val current = activity.defaultRoute()
            AlertDialog(onDismissRequest = { showRouteDialog = false },
                title = { Text("Default call route") },
                text = { Column { Text("Calls always start from this route without asking again.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp); Spacer(Modifier.height(10.dp))
                    listOf("SIP", "SIM 1", "SIM 2").forEach { r ->
                        val enabled = activity.routeAvailable(r)
                        TextButton(onClick = { activity.setDefaultRoute(null, r); showRouteDialog = false }, enabled = enabled) {
                            Text(if (current == r) "$r  ✓" else r, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = { activity.clearDefaultRoute(); showRouteDialog = false }) { Text("Clear default (ask every time)", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } },
                confirmButton = { TextButton(onClick = { showRouteDialog = false }) { Text("Close") } })
        }
        if (showDialerDialog) {
            AlertDialog(onDismissRequest = { showDialerDialog = false },
                title = { Text("Dialer & permissions") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (activity.isDefaultDialer()) "CallerX is your default dialer." else "CallerX is not the default dialer yet.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { activity.requestDefaultDialer(); showDialerDialog = false }) { Text("Set CallerX as default dialer") }
                    TextButton(onClick = { activity.requestStartupPermissions(); showDialerDialog = false }) { Text("Grant all permissions") }
                    TextButton(onClick = { activity.requestSmsPermission(); showDialerDialog = false }) { Text("Grant SMS access") }
                } },
                confirmButton = { TextButton(onClick = { showDialerDialog = false }) { Text("Close") } })
        }
        if (accountToDelete >= 0) {
            val acc = activity.accountsList().getOrNull(accountToDelete)
            AlertDialog(onDismissRequest = { accountToDelete = -1 },
                title = { Text("Delete SIP account") },
                text = { Text("Remove “${acc?.name?.ifBlank { acc?.user ?: "account" }}” from CallerX?") },
                confirmButton = { TextButton(onClick = { activity.removeAccount(accountToDelete); accountToDelete = -1 }) { Text("Delete", color = Color(0xFFD64545)) } },
                dismissButton = { TextButton(onClick = { accountToDelete = -1 }) { Text("Cancel") } })
        }
        if (showError && activity.errorMessage.isNotBlank()) AlertDialog(onDismissRequest = { showError = false; activity.errorMessage = "" }, title = { Text("CallerX") }, text = { Text(activity.errorMessage) }, confirmButton = { TextButton(onClick = { showError = false; activity.errorMessage = "" }) { Text("OK") } })
        if (showWelcome) AlertDialog(
            onDismissRequest = { showWelcome = false; prefs.edit().putBoolean("permission-intro-seen", true).apply() },
            title = { Text("Allow CallerX permissions") },
            text = { Text("Phone access lets CallerX start SIM calls, call-log and contacts access enables device data, and microphone access enables SIP audio. Android will ask you separately for each permission.") },
            confirmButton = { TextButton(onClick = { showWelcome = false; prefs.edit().putBoolean("permission-intro-seen", true).apply(); activity.requestStartupPermissions() }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { showWelcome = false; prefs.edit().putBoolean("permission-intro-seen", true).apply() }) { Text("Not now") } }
        )
    }
}

@Composable
private fun HomeHeader(
    tab: String,
    online: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    focus: FocusRequester,
    onSearchDone: () -> Unit,
    resultText: String,
    onSip: () -> Unit
) {
    /* Three lines, Google-Dialer style:
         1. CallerX
         2. SIM 1 · SIM 2 · SIP
         3. one full-width search bar (never taller than it needs to be)   */
    val subtitle = when (tab) {
        "Calls" -> "SIM 1 · SIM 2 · SIP"
        "Contacts" -> "SIM 1 · SIM 2 · SIP · contacts"
        "Messages" -> "SIM 1 · SIM 2 · SMS inbox"
        else -> "SIM 1 · SIM 2 · SIP"
    }
    val headerColor = MaterialTheme.colorScheme.primary
    val headerText = MaterialTheme.colorScheme.onPrimary
    Column(
        Modifier.fillMaxWidth().background(headerColor).statusBarsPadding()
            .padding(start = 18.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CallerX", fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, color = headerText)
                Text(subtitle, fontSize = 11.sp, color = headerText.copy(alpha = 0.85f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
            }
            OnlinePill(online, onSip)
        }
        Spacer(Modifier.height(9.dp))
        SearchField(query, onQuery, focus, onSearchDone, headerColor)
        if (resultText.isNotBlank()) {
            Text(resultText, fontSize = 10.sp, color = headerText.copy(alpha = 0.85f),
                modifier = Modifier.padding(start = 4.dp, top = 5.dp), maxLines = 1)
        }
    }
}

/* Compact full-width search box that lives inside the coloured header:
   exactly as wide as the header, no extra padding, one line high. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, focus: FocusRequester, onDone: () -> Unit, accent: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White, modifier = Modifier.fillMaxWidth().height(44.dp)) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, "Search", tint = Color(0xFF6B7681), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text("Search name, number or T9", fontSize = 13.5.sp, color = Color(0xFF8B95A1), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                /* Text keyboard on every tab: a numeric keyboard made name
                   search impossible, which is why search felt broken. */
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = Color(0xFF141A21)),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onDone() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
            if (query.isNotEmpty()) {
                Surface(onClick = { onQuery("") }, shape = CircleShape, color = Color.Transparent, modifier = Modifier.size(24.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Close, "Clear search", tint = Color(0xFF6B7681), modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlinePill(online: Boolean, onClick: () -> Unit) {
    // only "Online" / "Offline" on the right of the heading, like a status chip
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.18f)) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (online) Color(0xFF6EE7A8) else Color(0xFFFF9B9B)))
            Text(if (online) "Online" else "Offline", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
        }
    }
}

@Composable
private fun CallsTab(recents: List<Person>, filter: String, onFilter: (String) -> Unit, query: String, onAvatar: (Person) -> Unit, onCall: (Person) -> Unit, onMessage: (Person) -> Unit, onClearHistory: () -> Unit, onKeypad: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            /* chips scroll sideways so the "Clear" button never wraps ("Cle ar") */
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("All", "Missed", "Received", "Outgoing").forEach { label ->
                        FilterChip(selected = filter == label, onClick = { onFilter(label) }, label = { Text(label, maxLines = 1, softWrap = false, fontSize = 12.sp) }, shape = RoundedCornerShape(18.dp))
                    }
                }
                TextButton(onClick = onClearHistory, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text("Clear", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            }
            val filtered = remember(recents, filter, query) {
                recents.filter { p ->
                    val ok = when (filter) { "Outgoing" -> p.callType == CallLog.Calls.OUTGOING_TYPE; "Received" -> p.callType == CallLog.Calls.INCOMING_TYPE || p.callType == CallLog.Calls.ANSWERED_EXTERNALLY_TYPE; "Missed" -> p.callType == CallLog.Calls.MISSED_TYPE; else -> true }
                    ok && matchesQuery(p.name, p.number, query)
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 90.dp)) {
                if (filtered.isEmpty()) item { Text(if (query.isBlank()) "No recent calls found on this device." else "No calls match “$query”.", Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                itemsIndexed(filtered, key = { i, p -> "call-$i-${p.number}-${p.ts}" }) { _, p -> CallRow(p, onAvatar = { onAvatar(p) }, onCall = { onCall(p) }, onMessage = { onMessage(p) }) }
            }
        }
        FloatingActionButton(onClick = onKeypad, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 14.dp), shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White) { Icon(Icons.Filled.Dialpad, "Open keypad", modifier = Modifier.size(23.dp)) }
    }
}

@Composable
private fun CallRow(person: Person, onAvatar: () -> Unit, onCall: () -> Unit, onMessage: () -> Unit) {
    val missed = person.callType == CallLog.Calls.MISSED_TYPE
    val outgoing = person.callType == CallLog.Calls.OUTGOING_TYPE
    val tint = if (missed) Color(0xFFD64545) else if (outgoing) MaterialTheme.colorScheme.primary else Color(0xFF22A06B)
    val icon = if (missed) Icons.Filled.CallReceived else if (outgoing) Icons.Filled.CallMade else Icons.Filled.CallReceived
    var showMenu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onCall).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(tint.copy(alpha = 0.13f)).clickable(onClick = onAvatar), contentAlignment = Alignment.Center) {
            Text(person.initials.take(1).uppercase(), color = tint, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(person.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                if (person.spam) { Spacer(Modifier.width(6.dp)); Text("Spam", color = Color(0xFFC64646), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (missed) Icons.Filled.CallMissed else icon, null, tint = tint, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("${if (missed) "Missed" else if (outgoing) "Outgoing" else "Received"} · ${person.source}", color = if (missed) Color(0xFFD64545) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
            }
            Text(person.number, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(person.time, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            if (person.dur > 0) Text(fmtDur(person.dur), color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = onCall, modifier = Modifier.size(38.dp)) { Icon(Icons.Filled.Call, "Call", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp)) }
        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(38.dp)) { Icon(Icons.Filled.MoreVert, "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp)) }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(text = { Text("Call") }, onClick = { showMenu = false; onCall() })
                DropdownMenuItem(text = { Text("Message") }, onClick = { showMenu = false; onMessage() })
                DropdownMenuItem(text = { Text("Copy number") }, onClick = {
                    showMenu = false
                    try { clipboard.setText(AnnotatedString(person.number)); Toast.makeText(context, "Copied ${person.number}", Toast.LENGTH_SHORT).show() } catch (_: Exception) { }
                })
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 74.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun ContactsTab(contacts: List<Person>, query: String, favorites: Set<String>, onOpen: (Person) -> Unit, onCall: (Person) -> Unit, onMessage: (Person) -> Unit) {
    val visible = remember(contacts, query) { contacts.filter { matchesQuery(it.name, it.number, query) } }
    /* Real favorites (starred in contact details), not just the first five. */
    val favs = remember(visible, favorites) { visible.filter { it.number in favorites } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
        if (query.isBlank()) {
            item { SectionTitle("FAVORITES") }
            if (favs.isEmpty()) item { Text("No favorites yet. Star a contact in its details page.", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
            itemsIndexed(favs, key = { i, p -> "fav-$i-${p.number}" }) { _, p -> ContactRow(p, { onOpen(p) }, { onCall(p) }, { onMessage(p) }) }
        }
        item { SectionTitle(if (query.isBlank()) "ALL CONTACTS · ${contacts.size}" else "SEARCH RESULTS · ${visible.size}") }
        if (visible.isEmpty()) item { Text(if (query.isBlank()) "No matching contacts. Grant Contacts permission or add contacts to your device." else "No contact matches “$query”.", Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        itemsIndexed(visible, key = { i, p -> "con-$i-${p.number}" }) { _, p -> ContactRow(p, { onOpen(p) }, { onCall(p) }, { onMessage(p) }) }
    }
}

@Composable
private fun ContactRow(person: Person, onOpen: () -> Unit, onCall: () -> Unit, onMessage: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)).clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
            Text(person.initials.take(1).uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(person.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
            Text(person.number, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
        }
        IconButton(onClick = onCall, modifier = Modifier.size(38.dp)) { Icon(Icons.Filled.Call, "Call", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp)) }
        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(38.dp)) { Icon(Icons.Filled.MoreVert, "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp)) }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(text = { Text("Call") }, onClick = { showMenu = false; onCall() })
                DropdownMenuItem(text = { Text("Message") }, onClick = { showMenu = false; onMessage() })
                DropdownMenuItem(text = { Text("Copy number") }, onClick = {
                    showMenu = false
                    try { clipboard.setText(AnnotatedString(person.number)); Toast.makeText(context, "Copied ${person.number}", Toast.LENGTH_SHORT).show() } catch (_: Exception) { }
                })
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 74.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun MessagesTab(activity: MainActivity, messages: List<Pair<String,String>>, query: String = "") {
    val visible = remember(messages, query) {
        if (query.isBlank()) messages
        else messages.filter { matchesQuery(it.first, it.first, query) || it.second.contains(query.trim(), true) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (query.isBlank()) "${messages.size} messages" else "${visible.size} of ${messages.size} messages", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { activity.markAllSmsRead() }) { Text("Read all", fontSize = 12.sp) }
            }
        }
        if (messages.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No SMS messages found in the device inbox.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { activity.requestSmsPermission() }) { Text("Grant SMS access") }
            }
        }
        if (messages.isNotEmpty() && visible.isEmpty()) item { Text("No message matches “$query”.", Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        itemsIndexed(visible, key = { i, row -> "sms-$i-${row.first}" }) { _, row ->
            Row(Modifier.fillMaxWidth().clickable { }.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Text(row.first.take(1).uppercase(), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.first, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(row.second, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                }
            }
            HorizontalDivider(Modifier.padding(start = 74.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

/* ---------- Call method sheet ---------- */
@Composable
private fun CallSheetDialog(activity: MainActivity, number: String, name: String, setDefault: Boolean, onSetDefault: (Boolean) -> Unit, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    val sims = activity.simSlots()
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.background, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp)) {
                Text(name.ifBlank { "Dialed number" }, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(number, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
                RouteCard(icon = Icons.Filled.Language, tint = MaterialTheme.colorScheme.primary, title = "SIP",
                    pill = if (activity.registered) "Online" else "Offline", pillOk = activity.registered, onClick = { onChoose("SIP") })
                sims.forEach { (slot, carrier, _) ->
                    if (activity.simShown(slot)) {
                        Spacer(Modifier.height(12.dp))
                        RouteCard(icon = Icons.Filled.Call, tint = if (slot == 0) Color(0xFF1E88E5) else Color(0xFF10B981),
                            title = "SIM ${slot + 1}", pill = carrier.ifBlank { "SIM ${slot + 1}" }, pillOk = true, onClick = { onChoose("SIM ${slot + 1}") })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = setDefault, onCheckedChange = onSetDefault, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
                    Text("Set default for this contact", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.clickable { onSetDefault(!setDefault) })
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    Surface(onClick = onDismiss, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
                        Text("Cancel", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 26.dp, vertical = 11.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteCard(icon: ImageVector, tint: Color, title: String, pill: String, pillOk: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(tint), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(12.dp))
            Surface(shape = RoundedCornerShape(50), color = if (pillOk) Color(0xFF10B981).copy(alpha = 0.14f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), modifier = Modifier.weight(1f, fill = false)) {
                Text(pill, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (pillOk) Color(0xFF0E9F6E) else MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

/* ---------- Call screen ---------- */
@Composable
private fun NativeCallScreen(activity: MainActivity, dark: Boolean, onTheme: (Boolean) -> Unit, onAnswer: () -> Unit, onDecline: () -> Unit, onEnd: () -> Unit) {
    var hold by remember { mutableStateOf(false) }
    val isCell = TelecomCallBridge.currentCall != null
    val state = if (isCell) TelecomCallBridge.state else activity.callState
    val name = if (isCell) "Phone call" else activity.activeCallName.ifBlank { "Unknown caller" }
    val number = if (isCell) TelecomCallBridge.remoteNumber else activity.activeNumber
    val source = if (isCell) TelecomCallBridge.source else activity.activeCallSource
    val incoming = state.startsWith("Incoming", ignoreCase = true)
    val background = if (dark) Color(0xFF071426) else Color(0xFFF9FAFB)
    val primaryText = if (dark) Color(0xFFF8FAFC) else Color(0xFF111827)
    val secondaryText = if (dark) Color(0xFFA6B2C2) else Color(0xFF64748B)
    val sourceColor = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition()
    val pulse by transition.animateFloat(initialValue = 1f, targetValue = 1.07f, animationSpec = infiniteRepeatable(animation = tween(2200, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse))
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(activity.callStartedAt, activity.callConnected) {
        while (activity.callStartedAt > 0L) { tick = System.currentTimeMillis(); delay(1000) }
    }
    LaunchedEffect(TelecomCallBridge.state) {
        val s = TelecomCallBridge.state
        if (s == "Incoming cellular call" || s == "Call ended") activity.callStartedAt = 0L
        if (s == "Connected" && activity.callStartedAt == 0L) activity.callStartedAt = System.currentTimeMillis()
    }
    val durationSec = if (activity.callStartedAt > 0L) ((tick - activity.callStartedAt).coerceAtLeast(0L) / 1000L) else 0L
    val durationText = String.format("%02d:%02d", durationSec / 60, durationSec % 60)
    val quality = activity.callQuality()
    val qLabel = activity.qualityLabel(quality)
    val netLabel = activity.networkTypeLabel()
    val netTint = if (quality >= 3f || !source.startsWith("SIP")) Color(0xFF22A06B) else if (quality >= 2f) Color(0xFFD47622) else if (quality >= 0f) Color(0xFFD64545) else Color(0xFF5C6873)
    Box(Modifier.fillMaxSize().background(background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
            Spacer(Modifier.height(26.dp))
            Text(name, color = primaryText, fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(number, color = secondaryText, fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = .4.sp, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (incoming) Color(0xFFF59E0B) else Color(0xFF22A06B)))
                Text(state.ifBlank { "Connecting…" }, color = secondaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (activity.callStartedAt > 0L) CallInfoChip(Icons.Filled.History, "Duration $durationText", if (dark) Color(0xFF7DD8A8) else Color(0xFF1E7E52))
                CallInfoChip(Icons.Filled.SignalCellular4Bar, if (qLabel.isNotBlank() && source.startsWith("SIP")) "$netLabel · $qLabel" else netLabel, netTint)
                if (activity.recording) CallInfoChip(Icons.Filled.FiberManualRecord, "Recording", Color(0xFFE5484D))
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(190.dp).graphicsLayer { scaleX = pulse; scaleY = pulse; alpha = .22f }.clip(CircleShape).background(sourceColor.copy(alpha = .15f)))
                Box(Modifier.size(150.dp).graphicsLayer { scaleX = 2f - pulse; scaleY = 2f - pulse; alpha = .36f }.clip(CircleShape).background(sourceColor.copy(alpha = .12f)))
                Box(Modifier.size(100.dp).clip(CircleShape).background(sourceColor), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Call, "Call", tint = Color.White, modifier = Modifier.size(40.dp).graphicsLayer { rotationZ = if (incoming) -28f else 0f })
                }
            }
            Spacer(Modifier.weight(1.2f))
            if (incoming) {
                Row(Modifier.fillMaxWidth().padding(bottom = 26.dp), horizontalArrangement = Arrangement.spacedBy(46.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(onClick = onDecline, modifier = Modifier.size(66.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFDC3F45), contentColor = Color.White)) { Icon(Icons.Filled.CallEnd, "Decline", modifier = Modifier.size(28.dp)) }
                        Text("Decline", color = primaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(onClick = onAnswer, modifier = Modifier.size(66.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF1EAA62), contentColor = Color.White)) { Icon(Icons.Filled.Call, "Answer", modifier = Modifier.size(28.dp)) }
                        Text("Answer", color = primaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CallControl(Icons.Filled.MicOff, "Mute", activity.muted, dark, secondaryText) { activity.toggleMute(!activity.muted) }
                    CallControl(Icons.Filled.VolumeUp, "Speaker", activity.speaker, dark, secondaryText) { activity.setSpeakerOn(!activity.speaker) }
                    CallControl(Icons.Filled.Pause, if (hold) "Resume" else "Hold", hold, dark, secondaryText) { hold = !hold; activity.toggleHold(hold) }
                    if (!isCell) CallControl(Icons.Filled.FiberManualRecord, if (activity.recording) "Stop" else "Record", activity.recording, dark, secondaryText) { activity.toggleCallRecording() }
                }
                Spacer(Modifier.height(20.dp))
                FilledIconButton(onClick = onEnd, modifier = Modifier.size(66.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFDC3F45), contentColor = Color.White)) { Icon(Icons.Filled.CallEnd, "End call", modifier = Modifier.size(29.dp)) }
                Text("End call", color = primaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp, bottom = 22.dp))
            }
        }
    }
}

@Composable
private fun CallInfoChip(icon: ImageVector, text: String, tint: Color) {
    Surface(shape = RoundedCornerShape(50), color = tint.copy(alpha = 0.14f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
            Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tint)
        }
    }
}

@Composable
private fun CallControl(icon: ImageVector, label: String, selected: Boolean, dark: Boolean, secondaryText: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(54.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (selected) Color(0xFF2864DC) else if (dark) Color(0xFF1E2B3D) else Color(0xFFE9EDF3), contentColor = if (selected) Color.White else if (dark) Color.White else Color(0xFF334155))) { Icon(icon, label, modifier = Modifier.size(22.dp)) }
        Text(label, color = secondaryText, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

/* ---------- Contact details ---------- */
@Composable
private fun ContactDetailScreen(activity: MainActivity, person: Person, recents: List<Person>, onBack: () -> Unit, onCall: () -> Unit, onMessage: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("callerx", Context.MODE_PRIVATE) }
    val norm = person.number.filter { it.isDigit() || it == '+' }
    var fav by remember(person.number) { mutableStateOf(prefs.getBoolean("fav:$norm", false)) }
    var tags by remember(person.number) { mutableStateOf(try { val a = JSONArray(prefs.getString("tags:$norm", "[]") ?: "[]"); (0 until a.length()).map { a.getString(it) }.toMutableList() } catch (_: Exception) { mutableListOf<String>() }) }
    var note by remember(person.number) { mutableStateOf(prefs.getString("note:$norm", "") ?: "") }
    var showAddTag by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }
    var showAllCalls by remember { mutableStateOf(false) }
    var contactEmail by remember { mutableStateOf("") }
    val blocked = activity.isBlockedNumber(person.number)
    val history = remember(recents, norm) { recents.filter { digitsOnly(it.number) == digitsOnly(norm) } }
    val totalSec = remember(history) { history.sumOf { it.dur } }
    val primary = MaterialTheme.colorScheme.primary
    val secondaryText = MaterialTheme.colorScheme.onSurfaceVariant
    val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    LaunchedEffect(person.number) {
        contactEmail = withContext(Dispatchers.IO) {
            try {
                var email = ""
                context.contentResolver.query(Uri.parse("content://com.android.contacts/phone_lookup/" + Uri.encode(norm)), arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { c ->
                    if (c.moveToNext()) {
                        val id = c.getString(0)
                        context.contentResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS), ContactsContract.CommonDataKinds.Email.CONTACT_ID + "=?", arrayOf(id), null)?.use { e -> if (e.moveToNext()) email = e.getString(0).orEmpty() }
                    }
                }
                email
            } catch (_: Exception) { "" }
        }
    }

    fun openApp(url: String, missing: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { activity.errorMessage = missing }
    }
    fun openGmail() {
        val direct = Intent(Intent.ACTION_SENDTO, Uri.parse(if (contactEmail.isNotBlank()) "mailto:$contactEmail" else "mailto:"))
        direct.setPackage("com.google.android.gm")
        try { context.startActivity(direct) } catch (_: Exception) {
            try { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))) } catch (_: Exception) { activity.errorMessage = "No email app is installed on this device." }
        }
    }
    fun addContact() {
        val i = Intent(ContactsContract.Intents.Insert.ACTION)
        i.type = ContactsContract.Contacts.CONTENT_TYPE
        i.putExtra(ContactsContract.Intents.Insert.PHONE, norm)
        try { context.startActivity(i) } catch (_: Exception) { activity.errorMessage = "No contacts app is available on this device." }
    }
    val digits = norm.filter { it.isDigit() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { addContact() }) { Icon(Icons.Filled.Add, "Add to contacts", tint = MaterialTheme.colorScheme.onSurface) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(primary), contentAlignment = Alignment.Center) {
                Text(person.initials.take(1).uppercase(), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(person.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                if (blocked) Surface(shape = RoundedCornerShape(50), color = Color(0xFFD64545).copy(alpha = 0.12f)) { Text("BLOCKED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB33A3A), modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) }
            }
            Spacer(Modifier.height(18.dp))
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(person.number, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        Box(Modifier.size(46.dp).clip(CircleShape).background(primary.copy(alpha = 0.12f)).clickable(onClick = onCall), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Call, "Call", tint = primary, modifier = Modifier.size(19.dp)) }
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onMessage), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.Message, "Message", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(19.dp)) }
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp), color = divider)
                    AppLinkRow(Color(0xFF25D366), Icons.AutoMirrored.Filled.Message, "Message on WhatsApp") { openApp("https://wa.me/$digits", "WhatsApp is not installed on this device.") }
                    HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp), color = divider)
                    AppLinkRow(Color(0xFF229ED9), Icons.Filled.Send, "Message on Telegram") { openApp("https://t.me/+$digits", "Telegram is not installed on this device.") }
                    HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp), color = divider)
                    AppLinkRow(Color(0xFFEA4335), Icons.Filled.Mail, "Message on Gmail") { openGmail() }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Call logs", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text("Total ${fmtDur(totalSec).ifBlank { "0s" }}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = primary)
            }
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    val visibleHistory = if (showAllCalls) history else history.take(4)
                    if (visibleHistory.isEmpty()) Text("No calls with this number yet.", Modifier.padding(16.dp), fontSize = 12.sp, color = secondaryText)
                    visibleHistory.forEachIndexed { i, h ->
                        val missed = h.callType == CallLog.Calls.MISSED_TYPE
                        val outgoing = h.callType == CallLog.Calls.OUTGOING_TYPE
                        val typeWord = if (missed) "Missed" else if (outgoing) "Outgoing" else "Received"
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${relDay(h.ts)} ${h.time}".trim(), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                Text(h.source, fontSize = 12.sp, color = secondaryText)
                            }
                            Text("$typeWord ${fmtDur(h.dur)}".trim(), fontSize = 13.sp, color = if (missed) Color(0xFFD64545) else secondaryText)
                        }
                        if (i != visibleHistory.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp), color = divider)
                    }
                    if (history.size > 4) TextButton(onClick = { showAllCalls = !showAllCalls }, modifier = Modifier.fillMaxWidth()) { Text(if (showAllCalls) "Show less" else "Show all calls", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    SettingRow(Icons.Filled.Star, primary, "Favorite", if (fav) "Saved in your favorites" else "Add this contact to favorites", toggle = true, checked = fav, onClick = { fav = !fav; prefs.edit().putBoolean("fav:$norm", fav).apply() })
                    SettingRow(Icons.Filled.Edit, primary, "Edit contact", "Open the device contact editor", onClick = onEdit)
                    SettingRow(if (blocked) Icons.Filled.Check else Icons.Filled.Block, Color(0xFFD64545), if (blocked) "Unblock number" else "Block number", if (blocked) "Calls from this number are rejected" else "Reject calls from this number", onClick = { activity.setBlocked(person.number, !blocked) })
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text("TAGS", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = secondaryText)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    tags.forEachIndexed { i, t ->
                        AssistChip(onClick = { tags = tags.toMutableList().also { it.removeAt(i) }; prefs.edit().putString("tags:$norm", JSONArray(tags.toList()).toString()).apply() }, label = { Text(t, fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Filled.Close, null, modifier = Modifier.size(13.dp)) })
                    }
                    AssistChip(onClick = { showAddTag = !showAddTag }, label = { Text("+ Add tag", fontSize = 12.sp) })
                }
                if (showAddTag) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(newTag, { newTag = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("e.g. Office, VIP", fontSize = 12.sp) }, shape = RoundedCornerShape(14.dp))
                        TextButton(onClick = { if (newTag.isNotBlank()) { tags = tags.toMutableList().also { it.add(newTag.trim()) }; prefs.edit().putString("tags:$norm", JSONArray(tags.toList()).toString()).apply(); newTag = ""; showAddTag = false } }) { Text("Add") }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("NOTES", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = secondaryText)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(note, { note = it; prefs.edit().putString("note:$norm", it).apply() }, Modifier.fillMaxWidth(), placeholder = { Text("Add a note about this contact...", fontSize = 13.sp) }, minLines = 3, shape = RoundedCornerShape(18.dp))
            }
        }
    }
}

@Composable
private fun AppLinkRow(tint: Color, icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(tint), contentAlignment = Alignment.Center) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(17.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
    }
}



/* ---------- SIP accounts ---------- */
@Composable
private fun SipAccountsScreen(activity: MainActivity, onBack: () -> Unit, onAdd: () -> Unit, onEdit: (Int) -> Unit, onDelete: (Int) -> Unit, onToggle: (Int, Boolean) -> Unit, onMakeDefault: (Int) -> Unit) {
    val accounts = activity.accountsList()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("SIP Account", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("My SIP Accounts", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                        TextButton(onClick = onAdd) { Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp)); Text("Add", fontWeight = FontWeight.SemiBold) }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (accounts.isEmpty()) Text("No SIP account yet. Tap + Add to connect your VoIP provider.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
                    accounts.forEachIndexed { i, a ->
                        val online = activity.registered && a.isDefault
                        Surface(Modifier.fillMaxWidth().padding(vertical = 5.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = if (a.isDefault) 0.08f else 0.04f)) {
                            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (online) Icons.Filled.CheckCircle else Icons.Filled.CloudOff, null, tint = if (online) Color(0xFF22A06B) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(a.name.ifBlank { a.user }, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    Text("${a.user}@${a.domain}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (!a.enabled) "Disabled" else if (online) "Connected · used for outgoing calls" else activity.registrationMessage, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (online) Color(0xFF1E7E52) else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = a.enabled, onCheckedChange = { en -> onToggle(i, en) }, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary), modifier = Modifier.height(26.dp))
                                IconButton(onClick = { onEdit(i) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Edit, "Edit", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) }
                                IconButton(onClick = { onDelete(i) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Delete, "Delete", tint = Color(0xFFD64545), modifier = Modifier.size(16.dp)) }
                            }
                        }
                        if (!a.isDefault) TextButton(onClick = { onMakeDefault(i) }, modifier = Modifier.padding(start = 8.dp)) { Text("Use for outgoing calls", fontSize = 11.sp) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Registration status: ${activity.registrationMessage}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SipFormScreen(activity: MainActivity, editIndex: Int, onBack: () -> Unit, onSave: (SipAccount, Int) -> Unit) {
    val existing = if (editIndex >= 0) activity.accountsList().getOrNull(editIndex) else null
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var pass by remember { mutableStateOf(existing?.pass ?: "") }
    var domain by remember { mutableStateOf(existing?.domain ?: "") }
    var port by remember { mutableStateOf(existing?.port ?: "5060") }
    var transport by remember { mutableStateOf(existing?.transport ?: "UDP") }
    var proxy by remember { mutableStateOf(existing?.proxy ?: "") }
    var displayName by remember { mutableStateOf(existing?.displayName ?: "") }
    var autoBoot by remember { mutableStateOf(existing?.autoBoot ?: true) }
    var isDefault by remember { mutableStateOf(existing?.isDefault ?: activity.accountsList().isEmpty()) }
    var showPass by remember { mutableStateOf(false) }
    var showProxy by remember { mutableStateOf(false) }
    val ready = user.isNotBlank() && pass.isNotBlank() && domain.isNotBlank()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(if (editIndex >= 0) "Edit SIP Account" else "Add SIP Account", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Cloud, null, tint = Color.White, modifier = Modifier.size(19.dp)) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (editIndex >= 0) "Edit SIP Account" else "New SIP Account", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text("Extension, Domain & Protocol", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Auto-Boot", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Switch(checked = autoBoot, onCheckedChange = { autoBoot = it }, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary), modifier = Modifier.height(24.dp))
                        }
                    }
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Account Name *", fontSize = 12.sp) }, placeholder = { Text("e.g. Office, Home", fontSize = 13.sp) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(user, { user = it }, Modifier.weight(1f), label = { Text("Username *", fontSize = 12.sp) }, placeholder = { Text("1001", fontSize = 13.sp) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                        OutlinedTextField(pass, { pass = it }, Modifier.weight(1f), label = { Text("Password *", fontSize = 12.sp) }, singleLine = true, shape = RoundedCornerShape(14.dp),
                            visualTransformation = if (showPass) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            trailingIcon = { IconButton(onClick = { showPass = !showPass }) { Icon(if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show password", modifier = Modifier.size(18.dp)) } })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(domain, { domain = it }, Modifier.weight(1.6f), label = { Text("SIP Domain / IP *", fontSize = 12.sp) }, placeholder = { Text("sip.server.com", fontSize = 13.sp) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                        OutlinedTextField(port, { port = it }, Modifier.weight(0.8f), label = { Text("Port", fontSize = 12.sp) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf("UDP", "TCP", "TLS").forEach { t ->
                            FilterChip(selected = transport == t, onClick = { transport = t }, label = { Text(t, fontSize = 12.sp) }, shape = RoundedCornerShape(10.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showProxy = !showProxy }) { Text("Proxy / Name", fontSize = 12.sp); Icon(if (showProxy) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, modifier = Modifier.size(15.dp)) }
                    }
                    if (showProxy) {
                        OutlinedTextField(proxy, { proxy = it }, Modifier.fillMaxWidth(), label = { Text("Proxy / Outbound (optional)", fontSize = 12.sp) }, placeholder = { Text("sip.proxy.com:5060;transport=udp", fontSize = 12.sp) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                        OutlinedTextField(displayName, { displayName = it }, Modifier.fillMaxWidth(), label = { Text("Display name (optional)", fontSize = 12.sp) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isDefault, onCheckedChange = { isDefault = it }, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
                        Text("Use for outgoing calls (default)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { onSave(SipAccount(name.trim(), user.trim(), pass, domain.trim().removePrefix("sip:").substringBefore(';'), port.trim().ifBlank { "5060" }, transport, proxy.trim(), displayName.trim(), autoBoot, true, isDefault), editIndex) },
                        enabled = ready, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text("Save & Connect SIP", fontWeight = FontWeight.SemiBold)
                    }
                    if (!ready) Text("Fill username, password and SIP domain to enable saving.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/* ---------- Settings ---------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(activity: MainActivity, dark: Boolean, onDark: (Boolean) -> Unit, onSip: () -> Unit, onTheme: () -> Unit, onProfile: () -> Unit, onBlocked: () -> Unit, onRoute: () -> Unit, onDialer: () -> Unit, onPower: () -> Unit, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("callerx", Context.MODE_PRIVATE) }
    var sim1 by remember { mutableStateOf(prefs.getBoolean("setting:SIM 1", true)) }
    var sim2 by remember { mutableStateOf(prefs.getBoolean("setting:SIM 2", true)) }
    var ask by remember { mutableStateOf(prefs.getBoolean("setting:Ask before each call", false)) }
    var autoRec by remember { mutableStateOf(prefs.getBoolean("setting:autoRecord", false)) }
    val accounts = activity.accountsList()
    val defaultAcc = accounts.firstOrNull { it.isDefault }
    val route = activity.defaultRoute()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (onBack != null) {
            TopBar("Settings", onBack)
        } else {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, color = MaterialTheme.colorScheme.onPrimary)
                    Text("CallerX VoIP & Dual-SIM Preferences", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f), modifier = Modifier.padding(top = 2.dp))
                }
                OnlinePill(activity.registered, onSip)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 90.dp)) {
            SectionTitle("ACCOUNT")
            SettingRow(Icons.Filled.Person, MaterialTheme.colorScheme.primary, "My Profile & User Account", "CallerX profile · auto backup on", onProfile)
            SectionTitle("VOIP & SIP")
            SettingRow(Icons.Filled.Cloud, MaterialTheme.colorScheme.primary, "SIP Account", if (defaultAcc != null) "${defaultAcc.domain} · ${activity.registrationMessage}" else "No account added", onSip)
            SettingRow(Icons.Filled.GraphicEq, MaterialTheme.colorScheme.primary, "Audio & Codec", "Opus 16 kHz mono, G.722 · echo cancellation on", onRoute = null, onClick = { activity.errorMessage = "Codecs in use: Opus 16 kHz mono (low bandwidth, stable on 4G), G.722, PCMA, PCMU and DTMF events. Echo cancellation is always on." })
            SectionTitle("SIM CALLING & RECORDING")
            SettingRow(Icons.Filled.SimCard, MaterialTheme.colorScheme.primary, "SIM 1", "Show SIM 1 as a call option", toggle = true, checked = sim1, onClick = { sim1 = !sim1; prefs.edit().putBoolean("setting:SIM 1", sim1).apply() })
            SettingRow(Icons.Filled.SimCard, MaterialTheme.colorScheme.primary, "SIM 2", "Show SIM 2 as a call option", toggle = true, checked = sim2, onClick = { sim2 = !sim2; prefs.edit().putBoolean("setting:SIM 2", sim2).apply() })
            SettingRow(Icons.Filled.SwapCalls, MaterialTheme.colorScheme.primary, "Ask before each call", if (ask) "Choose SIP or an enabled SIM" else "Use the default route automatically", toggle = true, checked = ask, onClick = { ask = !ask; prefs.edit().putBoolean("setting:Ask before each call", ask).apply() })
            SettingRow(Icons.Filled.Mic, MaterialTheme.colorScheme.primary, "Auto Call Recording", if (autoRec) "On · every call is recorded (${activity.recordingCount()} saved)" else "Off · ${activity.recordingCount()} recordings saved", toggle = true, checked = autoRec, onClick = { autoRec = !autoRec; prefs.edit().putBoolean("setting:autoRecord", autoRec).apply() })
            SettingRow(Icons.Filled.Call, MaterialTheme.colorScheme.primary, "Default call route", if (route.isBlank()) "Not set — you are asked every time" else "All calls go via $route", onRoute)
            SectionTitle("GENERAL & SYSTEM")
            SettingRow(Icons.Filled.Phone, MaterialTheme.colorScheme.primary, "Dialer & Default Permission", if (activity.isDefaultDialer()) "CallerX is the default dialer" else "Set as default dialer · grant permissions", onDialer)
            SettingRow(Icons.Filled.Block, MaterialTheme.colorScheme.primary, "Blocked numbers", "${activity.blockedNumbers().size} numbers · calls are rejected", onBlocked)
            SettingRow(Icons.Filled.FlashOn, MaterialTheme.colorScheme.primary, "Power Mode", "Unrestricted · best for background SIP", onPower)
            SettingRow(Icons.Filled.Palette, MaterialTheme.colorScheme.primary, "Theme color", "Accent applies to the whole app", onTheme)
            SettingRow(Icons.Filled.DarkMode, MaterialTheme.colorScheme.primary, "Dark Mode", if (dark) "On" else "Off", toggle = true, checked = dark, onClick = { onDark(!dark) })
        }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, tint: Color, title: String, subtitle: String, onClick: () -> Unit, onRoute: (() -> Unit)? = null, toggle: Boolean = false, checked: Boolean = false) {
    val action = onRoute ?: onClick
    Row(Modifier.fillMaxWidth().clickable { if (!toggle) action() }.padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (toggle) Switch(checked = checked, onCheckedChange = { onClick() }, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
        else Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
    HorizontalDivider(Modifier.padding(start = 75.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}

@Composable
private fun BlockedScreen(activity: MainActivity, onBack: () -> Unit) {
    val blocked = activity.blockedNumbers()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("Blocked numbers", onBack)
        if (blocked.isEmpty()) Text("No blocked numbers. Block a number from its contact details page.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        LazyColumn {
            items(blocked) { n ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFD64545).copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Block, null, tint = Color(0xFFD64545), modifier = Modifier.size(17.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Text(n, Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    TextButton(onClick = { activity.setBlocked(n, false) }) { Text("Unblock", fontSize = 12.sp) }
                }
                HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            }
        }
    }
}

/* ---------- Misc screens ---------- */
@Composable
private fun TopBar(title: String, onBack: () -> Unit) { Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).statusBarsPadding().padding(top = 6.dp).padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onPrimary) }; Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary) } }

@Composable
private fun DialerScreen(number: String, onNumber: (String) -> Unit, contacts: List<Person>, onBack: () -> Unit, onCall: () -> Unit, onCallPerson: (Person) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val secondaryText = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    // gestures must always read the newest number, so keep it in a stable ref
    val numberRef = remember { mutableStateOf(number) }
    numberRef.value = number
    fun append(s2: String) { onNumber(numberRef.value + s2) }
    fun deleteLast() { val t = numberRef.value; if (t.isNotEmpty()) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onNumber(t.dropLast(1)) } }
    fun clearAll() { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onNumber("") }
    fun pasteNumber() {
        val raw = try { clipboard.getText()?.text.orEmpty() } catch (_: Exception) { "" }
        val clean = raw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (clean.isBlank()) Toast.makeText(context, "No phone number in the clipboard", Toast.LENGTH_SHORT).show()
        else { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onNumber(numberRef.value + clean); Toast.makeText(context, "Pasted $clean", Toast.LENGTH_SHORT).show() }
    }
    fun copyNumber() {
        if (numberRef.value.isBlank()) return
        try { clipboard.setText(AnnotatedString(numberRef.value)); Toast.makeText(context, "Copied ${numberRef.value}", Toast.LENGTH_SHORT).show() } catch (_: Exception) { }
    }
    fun insertPlus() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val t = numberRef.value
        if (!t.startsWith("+")) onNumber("+" + t)
    }
    // DTMF tones like a real dialer (Google Dialer / Truecaller behaviour)
    val toneGenerator = remember {
        try { ToneGenerator(AudioManager.STREAM_DTMF, 70) } catch (_: Exception) { null }
    }
    DisposableEffect(toneGenerator) {
        onDispose { try { toneGenerator?.stopTone(); toneGenerator?.release() } catch (_: Exception) { } }
    }
    fun playTone(digit: String) {
        val tone = when (digit) {
            "0" -> ToneGenerator.TONE_DTMF_0
            "1" -> ToneGenerator.TONE_DTMF_1
            "2" -> ToneGenerator.TONE_DTMF_2
            "3" -> ToneGenerator.TONE_DTMF_3
            "4" -> ToneGenerator.TONE_DTMF_4
            "5" -> ToneGenerator.TONE_DTMF_5
            "6" -> ToneGenerator.TONE_DTMF_6
            "7" -> ToneGenerator.TONE_DTMF_7
            "8" -> ToneGenerator.TONE_DTMF_8
            "9" -> ToneGenerator.TONE_DTMF_9
            "*" -> ToneGenerator.TONE_DTMF_S
            "#" -> ToneGenerator.TONE_DTMF_P
            else -> -1
        }
        if (tone >= 0) try { toneGenerator?.startTone(tone, 150) } catch (_: Exception) { }
    }
    // Live contact suggestions while typing (T9 + number), like Truecaller / Google Dialer
    val suggestions = remember(contacts, number) {
        if (number.length < 2) emptyList()
        else contacts.filter { matchesQuery(it.name, it.number, number) }.take(3)
    }
    // name of the contact that matches the typed digits (shown above the number)
    val matchName = suggestions.firstOrNull()?.name

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        /* ---- Top strip in the app theme colour: the colour is drawn behind the
               status bar as well, so no white band is left above the header ---- */
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).statusBarsPadding()
                .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onPrimary)
            }
            Text(
                "CallerX", Modifier.weight(1f), textAlign = TextAlign.Center,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.95f)
            )
            Spacer(Modifier.size(48.dp))   // balances the back arrow, keeps "CallerX" centred
        }

        /* ---- Middle area: contact suggestions, keypad is pinned to the bottom ---- */
        if (suggestions.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                suggestions.forEach { person ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .clickable { onNumber(person.number) }.padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            Text(person.initials.take(1).uppercase(), color = accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(person.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                            Text(person.number, color = secondaryText, fontSize = 12.sp, maxLines = 1)
                        }
                        IconButton(onClick = { onCallPerson(person) }, modifier = Modifier.size(38.dp)) { Icon(Icons.Filled.Call, "Call ${person.name}", tint = accent, modifier = Modifier.size(19.dp)) }
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        /* ---- Number display, right above the keypad (Truecaller placement).
               Long-press it to paste a copied number. ---- */
        NumberBar(
            number = number,
            matchName = if (number.length >= 3) matchName else null,
            accent = accent,
            secondaryText = secondaryText,
            onCopy = { copyNumber() },
            onDelete = { deleteLast() },
            onClear = { clearAll() },
            onPaste = { pasteNumber() }
        )
        Spacer(Modifier.height(6.dp))

        /* ---- Keypad: flat Truecaller-style keys, 3 columns ---- */
        val keys = listOf(
            "1" to "", "2" to "ABC", "3" to "DEF",
            "4" to "GHI", "5" to "JKL", "6" to "MNO",
            "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
            "*" to "", "0" to "+", "#" to ""
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            keys.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { (digit, letters) ->
                        DialKey(
                            digit = digit, letters = letters, modifier = Modifier.weight(1f),
                            onTap = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                playTone(digit)
                                append(digit)
                            },
                            /* long-press 0 types "+" (and a leading "+" when empty) */
                            onLongPress = if (digit == "0") ({ playTone(digit); insertPlus() }) else null
                        )
                    }
                }
            }
        }

        /* ---- Green call button, centred like Truecaller ---- */
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (number.isBlank()) Toast.makeText(context, "Enter a number first", Toast.LENGTH_SHORT).show() else onCall()
                },
                shape = CircleShape,
                color = if (number.isBlank()) Color(0xFF9CC7AE) else Color(0xFF2E9C67),
                shadowElevation = 6.dp,
                modifier = Modifier.size(68.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Call, "Call", tint = Color.White, modifier = Modifier.size(27.dp))
                }
            }
        }
    }
}

/* Number display placed just above the keypad (Truecaller style):
   copy on the left, big number in the middle, backspace on the right.
   A long-press anywhere on the box pastes a copied number. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NumberBar(
    number: String,
    matchName: String?,
    accent: Color,
    secondaryText: Color,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onPaste: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (number.isEmpty()) 0.30f else 0.5f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(72.dp)
    ) {
        Row(
            Modifier.fillMaxSize()
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { },
                    onLongClick = { onPaste() }
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (number.isEmpty()) Box(Modifier.size(44.dp)) else IconAction(
                Icons.Filled.ContentCopy, "Copy number", secondaryText, onTap = onCopy
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (matchName != null) {
                    Text(matchName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accent,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    if (number.isEmpty()) "Enter a number" else number,
                    fontSize = when {
                        number.isEmpty() -> 18.sp
                        number.length > 16 -> 21.sp
                        number.length > 11 -> 25.sp
                        else -> 32.sp
                    },
                    fontWeight = if (number.isEmpty()) FontWeight.Normal else FontWeight.Medium,
                    letterSpacing = 0.8.sp,
                    color = if (number.isEmpty()) secondaryText.copy(alpha = 0.65f) else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (number.isEmpty()) {
                    Text("press and hold to paste a copied number", fontSize = 9.5.sp,
                        color = secondaryText.copy(alpha = 0.55f), maxLines = 1)
                }
            }
            // backspace only when there is something to delete; long-press clears everything
            if (number.isEmpty()) Box(Modifier.size(44.dp)) else IconAction(
                Icons.Filled.Backspace, "Delete number", secondaryText,
                onTap = onDelete, onLongPress = onClear
            )
        }
    }
}

/* Small round icon action with ripple + optional long-press, used for copy,
   paste and backspace so they sit exactly where Truecaller places them. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean = true,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onTap,
                onLongClick = onLongPress
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = tint.copy(alpha = if (enabled) 1f else 0.3f), modifier = Modifier.size(21.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(
    digit: String,
    letters: String,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    /* Flat keys like Truecaller: big digit, small letter row, ripple on press.
       combinedClickable also gives TalkBack semantics and long-press support. */
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.height(60.dp).clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onTap,
                onLongClick = onLongPress
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(digit, fontSize = 27.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
            if (letters.isNotBlank()) {
                Text(letters, fontSize = 9.5.sp, letterSpacing = 1.4.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProfileScreen(activity: MainActivity, onBack: () -> Unit, onTheme: () -> Unit, onSettings: () -> Unit, onNotifications: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("callerx", Context.MODE_PRIVATE) }
    var name by remember { mutableStateOf(prefs.getString("profile:name", "") ?: "") }
    var mobile by remember { mutableStateOf(prefs.getString("profile:number", "") ?: "") }
    var email by remember { mutableStateOf(prefs.getString("profile:email", "") ?: "") }
    var status by remember { mutableStateOf(prefs.getString("profile:status", "Available · auto backup on") ?: "") }
    var photo by remember(activity.profileTick) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(activity.profileTick) {
        photo = withContext(Dispatchers.IO) {
            val f = File(context.filesDir, "profile_photo.jpg")
            if (f.exists()) try { android.graphics.BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null } else null
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("My Profile", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(primary), contentAlignment = Alignment.Center) {
                val bmp = photo
                if (bmp != null) Image(bmp.asImageBitmap(), "Profile photo", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(name.ifBlank { "C" }.first().uppercaseChar().toString(), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { activity.pickProfilePhoto() }) { Icon(Icons.Filled.PhotoCamera, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text("Change photo", fontSize = 12.sp) }
                if (photo != null) TextButton(onClick = { activity.clearProfilePhoto() }) { Text("Remove photo", fontSize = 12.sp, color = Color(0xFFD64545)) }
            }
            Text(name.ifBlank { "Your name" }, Modifier.padding(top = 2.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(mobile.ifBlank { "Mobile number not set" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(email.ifBlank { "Gmail not set" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(16.dp))
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Edit profile", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    OutlinedTextField(name, { name = it; prefs.edit().putString("profile:name", it).apply() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name", fontSize = 13.sp) }, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(mobile, { mobile = it; prefs.edit().putString("profile:number", it).apply() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Mobile number", fontSize = 13.sp) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(email, { email = it; prefs.edit().putString("profile:email", it).apply() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Gmail / email", fontSize = 13.sp) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(status, { status = it; prefs.edit().putString("profile:status", it).apply() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Status", fontSize = 13.sp) }, shape = RoundedCornerShape(14.dp))
                    Text("Changes are saved automatically on this device.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth()) {
                SettingRow(Icons.Filled.Settings, primary, "Notifications", "Open Android notification settings for CallerX", onClick = onNotifications)
                SettingRow(Icons.Filled.Palette, primary, "Theme", "Change the accent color of the whole app", onClick = onTheme)
                SettingRow(Icons.Filled.Settings, primary, "Settings", "Back to all settings", onClick = onSettings)
            }
        }
    }
}

@Composable
private fun ThemeScreen(selected: Int, onSelect: (Int) -> Unit, dark: Boolean, onDark: (Boolean) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("Theme color", onBack)
        Text("The selected accent is applied to every screen, including settings and the call UI.", Modifier.padding(horizontal = 18.dp, vertical = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.padding(horizontal = 16.dp)) {
            Palette.chunked(3).forEachIndexed { rowIndex, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEachIndexed { colIndex, color ->
                        val index = rowIndex * 3 + colIndex
                        Column(Modifier.clickable { onSelect(index) }.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(52.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) { if (selected == index) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                            Text(PaletteNames[index], Modifier.padding(top = 6.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingRow(Icons.Filled.DarkMode, MaterialTheme.colorScheme.primary, "Dark Mode", if (dark) "On" else "Off", toggle = true, checked = dark, onClick = { onDark(!dark) })
        }
    }
}

@Composable
private fun SectionTitle(label: String) { Text(label, Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
