package com.callerx.app

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
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
private data class Person(val name: String, val number: String, val initials: String, val verified: Boolean = false, val spam: Boolean = false, val time: String = "")
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
    context.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE), null, null, CallLog.Calls.DATE + " DESC")?.use { c ->
        while (c.moveToNext()) { val name = c.getString(0).orEmpty(); val number = c.getString(1).orEmpty(); val date = c.getLong(2); val missed = c.getInt(3) == CallLog.Calls.MISSED_TYPE; result.add(Person(name.ifBlank { number }, number, name.firstOrNull()?.toString() ?: "?", spam = false, time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(date)))) }
    }
    return result
}
private fun loadSms(context: Context): List<Pair<String,String>> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val rows = mutableListOf<Pair<String,String>>()
    context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY), null, null, Telephony.Sms.DATE + " DESC")?.use { c -> while (c.moveToNext()) rows.add((c.getString(0).orEmpty()) to c.getString(1).orEmpty()) }
    return rows
}

class MainActivity : ComponentActivity() {
    private var core: Core? = null
    var registered by mutableStateOf(false)
    var registrationMessage by mutableStateOf("SIP Offline")
    var callState by mutableStateOf("")
    var activeNumber by mutableStateOf("")
    var activeCallName by mutableStateOf("")
    private var activeCall: Call? = null
    var errorMessage by mutableStateOf("")
    var dialIntentNumber by mutableStateOf("")
    var callUiRequested by mutableStateOf(false)
    var dialerRoleTick by mutableIntStateOf(0)
    var resumeTick by mutableIntStateOf(0)
    private var pendingCellNumber = ""
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startSipCall(activeNumber, activeCallName) else errorMessage = "Microphone permission is needed for SIP audio calls."
    }
    private val cellPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) placeCellCall(pendingCellNumber) else errorMessage = "Phone permission is needed to start a cellular call."
    }
    private val runtimePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) errorMessage = "Some permissions were declined. You can enable them later in Android Settings."
    }
    private val defaultDialerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { dialerRoleTick++ }

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
            if (roles.isRoleHeld(RoleManager.ROLE_DIALER)) return
            defaultDialerLauncher.launch(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        } else {
            @Suppress("DEPRECATION")
            defaultDialerLauncher.launch(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName))
        }
    }

    fun startCellCall(number: String) {
        val cleaned = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (cleaned.isBlank()) { errorMessage = "Enter a phone number first."; return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCellNumber = cleaned
            cellPermission.launch(Manifest.permission.CALL_PHONE)
        } else placeCellCall(cleaned)
    }

    private fun placeCellCall(number: String) {
        try { startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))) }
        catch (e: Exception) { errorMessage = "Could not start cellular call: ${e.message ?: "check phone permission"}" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
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
            if (TelecomCallBridge.currentCall != null) { activity.activeNumber = TelecomCallBridge.remoteNumber; activity.activeCallName = "Phone call" }
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
            NativeCallScreen(activity, visibleCallName, visibleCallNumber, visibleCallState, muted, speaker,
                onMute = { muted = !muted; activity.toggleMute(muted) }, onSpeaker = { speaker = !speaker; activity.setSpeaker(speaker) }, onAnswer = { if (isCellCall) activity.answerCellCall() else activity.answerCall() }, onEnd = { if (isCellCall) activity.endCellCall() else activity.endCall(); afterCall = true; screen = "summary" })
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
                floatingActionButton = if (tab == "Calls") ({ FloatingActionButton(onClick = { screen = "dialer" }, containerColor = color, contentColor = Color.White) { Icon(Icons.Filled.Dialpad, "Open dialer") } }) else ({ }),
                bottomBar = { NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("Calls" to Icons.Filled.Call, "Messages" to Icons.AutoMirrored.Filled.Message, "Contacts" to Icons.Filled.Contacts, "Settings" to Icons.Filled.Settings).forEach { (label, icon) ->
                        NavigationBarItem(selected = tab == label, onClick = { tab = label; searchOpen = false }, icon = { Icon(icon, label) }, label = { Text(label) }, alwaysShowLabel = true)
                    }
                } }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (tab == "Settings") {
                        SettingsScreen(activity, dark, { dark = it; prefs.edit().putBoolean("dark", it).apply() }, onSip = { screen = "sip" }, onTheme = { screen = "theme" }, onProfile = { screen = "profile" }, onBack = null)
                    } else {
                        Header(tab, activity.registrationMessage, searchOpen, { searchOpen = !searchOpen }, query, { query = it }, { screen = "profile" }, { screen = "sip" })
                        if (tab == "Calls") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                listOf("All", "Missed", "Spam", "Favorites").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) }) }
                            }
                            val filtered = recentCalls.filter { person ->
                                (filter == "All" || (filter == "Spam" && person.spam) || (filter == "Favorites" && person.verified) || (filter == "Missed" && person.name == "Spam Likely")) &&
                                    (query.isBlank() || person.name.contains(query, true) || person.number.contains(query, true))
                            }
                            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) { if (filtered.isEmpty()) item { Text("No recent calls found on this device.", Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }; items(filtered) { person -> CallRow(person, color, onAvatar = { selected = person }, onCall = { selected = person; showCallSheet = true }) } }
                        } else if (tab == "Contacts") {
                            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                                item { SectionTitle("FAVORITES") }
                                items(contacts.take(5)) { CallRow(it, color, onAvatar = { selected = it }, onCall = { selected = it; showCallSheet = true }) }
                                item { SectionTitle("ALL CONTACTS") }
                                if (contacts.isEmpty()) item { Text("No contacts found. Grant Contacts permission or add contacts to your device.", Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }; items(contacts) { CallRow(it, color, onAvatar = { selected = it }, onCall = { selected = it; showCallSheet = true }) }
                            }
                        } else {
                            LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
                                if (messages.isEmpty()) item { Text("No SMS messages found. Grant SMS permission to view your inbox.", Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }; itemsIndexed(messages) { i, row ->
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
                else { activity.startCellCall(dialedNumber); screen = "home" }
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
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, top = 76.dp, end = 16.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onProfile) { Avatar("R", MaterialTheme.colorScheme.primary, 36) }
            if (searchOpen) OutlinedTextField(query, onQuery, Modifier.weight(1f).height(56.dp), placeholder = { Text("Search name or number") }, singleLine = true, shape = RoundedCornerShape(22.dp))
            else Text(if (tab == "Calls") "CallerX" else tab, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = onSearch) { Icon(if (searchOpen) Icons.Filled.Close else Icons.Filled.Search, "Search") }
        }
        if (tab == "Calls") {
            Spacer(Modifier.height(9.dp))
            AssistChip(onClick = onSip, label = { Text(sip, fontWeight = FontWeight.SemiBold) }, leadingIcon = { Icon(Icons.Filled.Cloud, null, tint = if (sip == "SIP Online") Color(0xFF2E9C67) else Color(0xFFD34F4F)) })
        }
    }
}

@Composable private fun CallRow(person: Person, color: Color, onAvatar: () -> Unit, onCall: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onCall).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(person.initials, if (person.spam) Color(0xFFC64646) else color, 48, Modifier.clickable(onClick = onAvatar))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(person.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); if (person.verified) { Spacer(Modifier.width(5.dp)); Icon(Icons.Filled.Verified, null, tint = color, modifier = Modifier.size(16.dp)) }; if (person.spam) { Spacer(Modifier.width(6.dp)); Text("Spam", color = Color(0xFFC64646), fontSize = 11.sp, fontWeight = FontWeight.Bold) } }
            Text("${if (person.spam) "Missed call" else "Incoming call"} · ${person.number}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable private fun NativeCallScreen(activity: MainActivity, name: String, number: String, state: String, muted: Boolean, speaker: Boolean, onMute: () -> Unit, onSpeaker: () -> Unit, onAnswer: () -> Unit, onEnd: () -> Unit) {
    var hold by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF10284E), Color(0xFF071326), Color(0xFF020811)))).padding(24.dp)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(28.dp)); AssistChip(onClick = {}, label = { Text("SIP VOIP · HD AUDIO", color = Color(0xFF8EDAFF)) }, leadingIcon = { Icon(Icons.Filled.GraphicEq, null, tint = Color(0xFF63C8FF)) }, colors = AssistChipDefaults.assistChipColors(containerColor = Color.White.copy(alpha = .1f)))
            Spacer(Modifier.height(34.dp)); Text(name, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold); Text(number, color = Color.White.copy(alpha = .7f), fontSize = 15.sp); Spacer(Modifier.height(12.dp)); Text(state.ifBlank { "Connecting…" }, color = Color.White.copy(alpha = .8f))
            Spacer(Modifier.weight(1f)); Box(Modifier.size(210.dp).clip(CircleShape).background(Color(0xFF1681CC).copy(alpha = .10f)), contentAlignment = Alignment.Center) { Box(Modifier.size(160.dp).clip(CircleShape).background(Color(0xFF1681CC).copy(alpha = .18f)), contentAlignment = Alignment.Center) { Box(Modifier.size(112.dp).clip(CircleShape).background(Color(0xFF087DB8)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Call, null, tint = Color.White, modifier = Modifier.size(44.dp)) } } }
            Spacer(Modifier.weight(1f)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CallControl(Icons.Filled.MicOff, "Mute", muted, onMute); CallControl(Icons.Filled.VolumeUp, "Speaker", speaker, onSpeaker); CallControl(Icons.Filled.Pause, "Hold", hold) { hold = !hold }
            }
            if (state == "Incoming SIP call") { FilledIconButton(onClick = onAnswer, modifier = Modifier.size(66.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF2E9C67), contentColor = Color.White)) { Icon(Icons.Filled.Call, "Answer", modifier = Modifier.size(30.dp)) }; Text("Answer", color = Color.White, modifier = Modifier.padding(top = 5.dp)); Spacer(Modifier.height(14.dp)) }
            Spacer(Modifier.height(12.dp)); FilledIconButton(onClick = onEnd, modifier = Modifier.size(76.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE34848), contentColor = Color.White)) { Icon(Icons.Filled.CallEnd, "End call", modifier = Modifier.size(34.dp)) }; Text(if (state == "Incoming SIP call") "Decline" else "End", color = Color.White, modifier = Modifier.padding(top = 6.dp)); Spacer(Modifier.height(24.dp))
        }
    }
}
@Composable private fun CallControl(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) { Column(horizontalAlignment = Alignment.CenterHorizontally) { FilledIconButton(onClick = onClick, colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (selected) Color.White else Color.White.copy(alpha = .13f), contentColor = if (selected) CallerBlue else Color.White)) { Icon(icon, label) }; Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp)) } }
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
