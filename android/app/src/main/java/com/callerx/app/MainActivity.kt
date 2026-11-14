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

private val CallerBlue = Color(0xFF087DB8)
private val Palette = listOf(
    Color(0xFF087DB8), Color(0xFF16845B), Color(0xFF7053B5), Color(0xFFD47622),
    Color(0xFFC34F86), Color(0xFF455FC2), Color(0xFF168C8C), Color(0xFFCA4545), Color(0xFF68717D)
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
        } catch (e: Exception) {
            errorMessage = "VoIP engine couldn't start: ${e.message ?: "unknown error"}"
        }
    }

    fun registerSip(username: String, password: String, domain: String, port: String, transport: String) {
        val c = core ?: run { initializeLinphone(); core } ?: return
        try {
            c.clearAccounts()
            c.clearAllAuthInfo()
            val cleanDomain = domain.trim().removePrefix("sip:").substringBefore(';')
            val auth = Factory.instance().createAuthInfo(username.trim(), null, password, null, null, cleanDomain)
            val params = c.createAccountParams()
            val identity = Factory.instance().createAddress("sip:${username.trim()}@$cleanDomain")
            val server = Factory.instance().createAddress("sip:$cleanDomain:${port.trim()};transport=${transport.lowercase()}")
            params.setIdentityAddress(identity)
            params.setServerAddress(server)
            params.setRegisterEnabled(true)
            val account = c.createAccount(params)
            c.addAuthInfo(auth)
            c.addAccount(account)
            c.defaultAccount = account
            registrationMessage = "Registering…"
        } catch (e: Exception) {
            registered = false
            registrationMessage = "SIP Offline"
            errorMessage = "SIP setup failed: ${e.message ?: "check the account details"}"
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
            activeCall = c.inviteAddress(address)
            callState = "Calling…"
        } catch (e: Exception) { errorMessage = "Call could not start: ${e.message ?: "network unavailable"}" }
    }

    fun answerCall() { try { core?.currentCall?.let { it.accept() } } catch (e: Exception) { errorMessage = "Could not answer call: ${e.message}" } }
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
            SipScreen(activity.registrationMessage, sipUsername, { sipUsername = it }, sipPassword, { sipPassword = it }, sipDomain, { sipDomain = it }, sipPort, { sipPort = it }, transport, { transport = it },
                onBack = { screen = "settings" }, onRegister = {
                    prefs.edit().putString("user", sipUsername).putString("domain", sipDomain).putString("port", sipPort).putString("transport", transport).apply()
                    activity.registerSip(sipUsername, sipPassword, sipDomain, sipPort, transport)
                })
        } else if (screen == "dialer") {
            DialerScreen(number, { number = it }, onBack = { screen = "home" }, onCall = { selected = Person("Dialed number", number, "?"); showCallSheet = true })
        } else if (screen == "profile") {
            ProfileScreen(onBack = { screen = "home" }, onTheme = { screen = "theme" }, onSettings = { screen = "settings" })
        } else if (screen == "theme") {
            ThemeScreen(accent, { accent = it; prefs.edit().putInt("accent", it).apply() }, onBack = { screen = "settings" })
        } else if (screen == "settings") {
            SettingsScreen(activity, dark, { dark = it; prefs.edit().putBoolean("dark", it).apply() }, onSip = { screen = "sip" }, onTheme = { screen = "theme" }, onProfile = { screen = "profile" }, onBack = { screen = "home" })
        } else if (selected != null) {
            ContactDetailScreen(selected!!, onBack = { selected = null }, onCall = { showCallSheet = true })
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
                        SettingsScreen(activity, dark, { dark = it; prefs.edit().putBoolean("dark", it).apply() }, onSip = { screen = "sip" }, onTheme = { screen = "theme" }, onProfile = { screen = "profile" }, onBack = null)
                    } else {
                        Header(tab, activity.registrationMessage, searchOpen, { searchOpen = !searchOpen }, query, { query = it }, { screen = "profile" }, { screen = "sip" })
                        if (tab == "Calls") {
                            Box(Modifier.weight(1f)) {
                                Column(Modifier.fillMaxSize()) {
                            var source by remember { mutableStateOf("All Sources") }
                            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text("Recents", fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.4).sp, color = MaterialTheme.colorScheme.primary); Text("SIM 1, SIM 2 & SIP Call History", fontSize = 11.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp)) }
                                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) { Icon(Icons.Filled.Search, "Search calls", tint = Color(0xFF64748B)) }
                                IconButton(onClick = { filter = "All"; source = "All Sources"; query = ""; searchOpen = false }) { Icon(Icons.Filled.Refresh, "Reset filters", tint = Color(0xFF64748B)) }
                            }
                            if (searchOpen) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), singleLine = true, placeholder = { Text("Search name or number") }, leadingIcon = { Icon(Icons.Filled.Search, null) })
                            Row(Modifier.fillMaxWidth().background(Color(0xFFF8FAFC)).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("All", "Missed", "Received", "Outgoing").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label, maxLines = 1, fontSize = 12.sp) }, shape = RoundedCornerShape(18.dp)) }
                            }
                            Row(Modifier.fillMaxWidth().background(Color(0xFFF8FAFC)).padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("All Sources", "SIM 1", "SIM 2", "SIP").forEach { label -> FilterChip(selected = source == label, onClick = { source = label }, label = { Text(label, fontSize = 12.sp) }, shape = RoundedCornerShape(16.dp)) }
                            }
                            val filtered = recentCalls.filter { person ->
                                val categoryMatches = when (filter) { "Outgoing" -> person.callType == CallLog.Calls.OUTGOING_TYPE; "Received" -> person.callType == CallLog.Calls.INCOMING_TYPE; "Missed" -> person.callType == CallLog.Calls.MISSED_TYPE; else -> true }
                                val sourceMatches = source == "All Sources" || person.source == source
                                categoryMatches && sourceMatches && (query.isBlank() || person.name.contains(query, true) || person.number.contains(query, true))
                            }
                            LazyColumn(Modifier.weight(if (keypadOpen) .55f else 1f), contentPadding = PaddingValues(bottom = 4.dp)) { if (filtered.isEmpty()) item { Text("No recent calls found on this device.", Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }; items(filtered) { person -> CallRow(person, color, onAvatar = { selected = person }, onCall = { selected = person; showCallSheet = true }) } }
                            if (keypadOpen) KeypadPanel(number, onNumber = { number = it }, onCall = { selected = Person("Dialed number", number, "?"); showCallSheet = true }, onHide = { keypadOpen = false })
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
                                items(visibleContacts.take(5)) { CallRow(it, color, onAvatar = { selected = it }, onCall = { selected = it; showCallSheet = true }) }
                                item { SectionTitle(if (query.isBlank()) "ALL CONTACTS" else "SEARCH RESULTS") }
                                if (visibleContacts.isEmpty()) item { Text("No matching contacts. Grant Contacts permission or add contacts to your device.", Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }; items(visibleContacts) { CallRow(it, color, onAvatar = { selected = it }, onCall = { selected = it; showCallSheet = true }) }
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
            AlertDialog(onDismissRequest = { showCallSheet = false }, title = { Text("Call with…") }, text = { Column { Text(selected?.number ?: number, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(14.dp)); (listOf("SIP / VoIP") + listOf("SIM 1", "SIM 2").filter { prefs.getBoolean("setting:$it", false) }).forEach { method -> TextButton(onClick = {
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
@Composable private fun SectionTitle(label: String) { Text(label, Modifier.fillMaxWidth().padding(start = 18.dp, top = 18.dp, bottom = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SipScreen(status: String, user: String, onUser: (String) -> Unit, password: String, onPassword: (String) -> Unit, domain: String, onDomain: (String) -> Unit, port: String, onPort: (String) -> Unit, transport: String, onTransport: (String) -> Unit, onBack: () -> Unit, onRegister: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("SIP Account", onBack)
        Column(Modifier.fillMaxSize().padding(18.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Registration status", fontWeight = FontWeight.SemiBold); Text("${status}", color = if (status == "SIP Online") Color(0xFF2E9C67) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }; if (status == "SIP Online") Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF2E9C67)) } }
            OutlinedTextField(user, onUser, Modifier.fillMaxWidth(), label = { Text("SIP username") }, singleLine = true)
            OutlinedTextField(password, onPassword, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true)
            OutlinedTextField(domain, onDomain, Modifier.fillMaxWidth(), label = { Text("SIP server / domain") }, placeholder = { Text("sip.example.com") }, singleLine = true)
            OutlinedTextField(port, onPort, Modifier.fillMaxWidth(), label = { Text("Port") }, singleLine = true)
            Text("Transport", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("UDP", "TCP", "TLS").forEach { FilterChip(selected = transport == it, onClick = { onTransport(it) }, label = { Text(it) }) } }
            Text("Use the signaling details supplied by your SIP provider. SIP password is kept in memory for this session and is not saved.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onRegister, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Filled.CloudDone, null); Spacer(Modifier.width(8.dp)); Text("Save & Register") }
            OutlinedButton(onClick = { }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.MenuBook, null); Spacer(Modifier.width(8.dp)); Text("SIP setup guide") }
        }
    }
}

@Composable private fun TopBar(title: String, onBack: () -> Unit) { Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(top = 24.dp).padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }; Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) } }

@Composable private fun SettingsScreen(activity: MainActivity, dark: Boolean, onDark: (Boolean) -> Unit, onSip: () -> Unit, onTheme: () -> Unit, onProfile: () -> Unit, onBack: (() -> Unit)?) {
    val isDefaultDialer = activity.dialerRoleTick >= 0 && activity.isDefaultDialer()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (onBack != null) TopBar("Settings", onBack) else Text("Settings", Modifier.padding(start = 18.dp, top = 36.dp, end = 18.dp, bottom = 18.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        LazyColumn {
            item { SectionTitle("ACCOUNT") }; item { SettingRow(Icons.Filled.Person, "My Profile", "Manage your account", onProfile) }
            item { SectionTitle("VOIP & SIP") }; item { SettingRow(Icons.Filled.Cloud, "SIP Account", "Register your VoIP account", onSip) }
            item { SectionTitle("SIM CALLING") }; item { SettingRow(Icons.Filled.SimCard, "SIM 1", "Show SIM 1 as a call option", {}, true) }; item { SettingRow(Icons.Filled.SimCard, "SIM 2", "Show SIM 2 as a call option", {}, true) }; item { SettingRow(Icons.Filled.SwapCalls, "Ask before each call", "Choose SIP or an enabled SIM", {}, true, true) }
            item { SectionTitle("GENERAL") }; item { SettingRow(Icons.Filled.PhoneAndroid, "Set as Default Dialer", if (isDefaultDialer) "CallerX is your default dialer" else "Choose CallerX for SIM calls", activity::requestDefaultDialer) }; item { SettingRow(Icons.Filled.Palette, "Theme color", "Choose your accent", onTheme) }; item { SettingRow(Icons.Filled.DarkMode, "Dark Mode", "", { onDark(!dark) }, true, dark) }
        }
    }
}
@Composable private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, toggle: Boolean = false, checked: Boolean = false) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("callerx", Context.MODE_PRIVATE) }
    var value by remember(title, checked) { mutableStateOf(prefs.getBoolean("setting:$title", checked)) }
    val activate = { if (toggle) { value = !value; prefs.edit().putBoolean("setting:$title", value).apply() }; onClick() }
    Row(Modifier.fillMaxWidth().clickable { activate() }.padding(horizontal = 17.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .11f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(13.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp); if (subtitle.isNotBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
        if (toggle) Switch(value, onCheckedChange = { checkedValue -> value = checkedValue; prefs.edit().putBoolean("setting:$title", value).apply(); onClick() }) else Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .38f))
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
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { TopBar("My Profile", onBack); Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) { Avatar("R", MaterialTheme.colorScheme.primary, 90); Text("Rakib Hasan", Modifier.padding(top = 10.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("+880 17XX-XXXXXX", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("Available · Dhaka", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }; SettingRow(Icons.Filled.Edit, "Edit Profile", "Update your details", {}) ; SettingRow(Icons.Filled.Notifications, "Notifications", "", {}) ; SettingRow(Icons.Filled.Settings, "Settings", "", onSettings); SettingRow(Icons.Filled.Palette, "Theme", "Change app colors", onTheme) }
}
@Composable private fun ThemeScreen(selected: Int, onSelect: (Int) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { TopBar("Theme color", onBack); Text("Choose an accent", Modifier.padding(18.dp), fontWeight = FontWeight.SemiBold); Column(Modifier.padding(horizontal = 16.dp)) { Palette.chunked(3).forEachIndexed { rowIndex, row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { row.forEachIndexed { colIndex, color -> val index = rowIndex * 3 + colIndex; Column(Modifier.clickable { onSelect(index) }.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(52.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) { if (selected == index) Icon(Icons.Filled.Check, null, tint = Color.White) }; Text(listOf("Blue", "Green", "Purple", "Orange", "Pink", "Indigo", "Teal", "Red", "Gray")[index], Modifier.padding(top = 6.dp), fontSize = 12.sp) } } } } } }
}
