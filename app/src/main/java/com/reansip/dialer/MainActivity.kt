package com.reansip.dialer

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.reansip.dialer.core.CoreManager
import com.reansip.dialer.core.ReanCoreService
import com.reansip.dialer.data.SipAccount
import com.reansip.dialer.data.SipAccountStore
import org.json.JSONObject
import org.linphone.core.Call
import org.linphone.core.RegistrationState

/**
 * Renders the UI supplied by the user, unmodified in structure, from local app
 * assets. Native code only supplies Android permissions and the SIP engine.
 */
class MainActivity : AppCompatActivity(), CoreManager.Events {

    companion object {
        const val ACTION_SHOW_INCOMING = "com.reansip.dialer.action.SHOW_INCOMING"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_NAME = "name"
    }

    private lateinit var webView: WebView
    private var pageReady = false
    private var pendingIncoming: Pair<String, String>? = null
    private var afterPermissions: (() -> Unit)? = null

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val microphoneAllowed = result[Manifest.permission.RECORD_AUDIO]
            ?: hasPermission(Manifest.permission.RECORD_AUDIO)
        val next = afterPermissions
        afterPermissions = null
        if (microphoneAllowed) {
            next?.invoke()
        } else {
            sendToUi("callerXError(${js(getString(R.string.permission_microphone))})")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            result[Manifest.permission.POST_NOTIFICATIONS] == false
        ) {
            Toast.makeText(this, R.string.permission_notifications, Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        setContentView(R.layout.activity_callerx)
        webView = findViewById(R.id.callerxWebView)
        webView.setBackgroundColor(android.graphics.Color.BLACK)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            mediaPlaybackRequiresUserGesture = true
        }
        webView.addJavascriptInterface(CallerXBridge(this), "CallerXNative")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // This UI is bundled with the APK. Keeping navigation local protects the
                // deliberately narrow Javascript interface above.
                return request.url.scheme != "file"
            }
        }
        webView.loadUrl("file:///android_asset/callerx-ui.html")

        CoreManager.ensureCore(this)
        consumeIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        CoreManager.addListener(this)
        if (SipAccountStore(this).activeAccount() != null && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            ReanCoreService.start(this)
        }
    }

    override fun onPause() {
        CoreManager.removeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("CallerXNative")
        webView.destroy()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        if (intent?.action != ACTION_SHOW_INCOMING) return
        val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        if (number.isNotBlank()) showIncoming(name, number)
    }

    /** Called only by the known local asset after its DOM and its supplied JS are ready. */
    fun onCallerXUiReady() {
        pageReady = true
        hydrateSipUi()
        pendingIncoming?.let { (name, number) ->
            pendingIncoming = null
            showIncoming(name, number)
        }
    }

    fun saveSipFromUi(payload: String) {
        val data = try {
            JSONObject(payload)
        } catch (_: Throwable) {
            sendToUi("callerXError(${js("Invalid SIP account details")})")
            return
        }
        val username = data.optString("username").trim()
        val server = data.optString("server").trim()
        if (username.isBlank() || server.isBlank()) {
            sendToUi("callerXError(${js("Username and SIP server are required")})")
            return
        }
        withCallPermissions {
            val store = SipAccountStore(this)
            val previous = store.activeAccount()
            val port = data.optString("port").trim()
            val domain = server.removePrefix("sip:").removePrefix("sips:").removeSuffix("/")
                .let { raw ->
                    if (port.isBlank() || raw.substringAfterLast(':', "").all { it.isDigit() }) raw
                    else "$raw:$port"
                }
            val newPassword = data.optString("password")
            val account = SipAccount(
                id = previous?.id ?: System.currentTimeMillis(),
                label = previous?.label ?: "CallerX SIP",
                username = username,
                password = newPassword.ifBlank { previous?.password.orEmpty() },
                domain = domain,
                transport = data.optString("transport", "TLS").uppercase(),
                displayName = previous?.displayName.orEmpty(),
                registerEnabled = true
            )
            store.upsert(account)
            store.setActive(account.id)
            CoreManager.applyAccounts(this)
            ReanCoreService.start(this)
            sendToUi("callerXOnSipSaved()")
        }
    }

    fun startSipCallFromUi(number: String) {
        withCallPermissions {
            if (SipAccountStore(this).activeAccount() == null) {
                sendToUi("callerXError(${js(getString(R.string.sip_not_ready))})")
                return@withCallPermissions
            }
            ReanCoreService.start(this)
            CoreManager.applyAccounts(this)
            if (!CoreManager.startCall(this, number)) {
                sendToUi("callerXError(${js(getString(R.string.invalid_number))})")
            }
        }
    }

    fun openSystemDialer(number: String) {
        val target = number.replace(Regex("[^0-9+*#]"), "")
        if (target.isBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", target, null)))
        } catch (_: Throwable) {
            sendToUi("callerXError(${js("No phone dialer is available")})")
        }
    }

    fun answerCallFromUi() {
        CoreManager.currentCall()?.let(CoreManager::answer)
    }

    fun endCallFromUi() {
        CoreManager.hangup(CoreManager.currentCall())
    }

    fun setMutedFromUi(muted: Boolean) {
        CoreManager.setMicMuted(muted)
    }

    fun setSpeakerFromUi(on: Boolean) {
        CoreManager.currentCall()?.let { CoreManager.routeSpeaker(it, on) }
    }

    fun toggleHoldFromUi() {
        CoreManager.currentCall()?.let(CoreManager::toggleHold)
    }

    /** Recording remains opt-in from the record button already present in the supplied UI. */
    fun toggleRecordingFromUi() {
        CoreManager.toggleRecording()
    }

    fun setAutoRecordingFromUi(enabled: Boolean) {
        CoreManager.setRecordingEnabled(enabled)
    }

    fun requestCallPermissionsFromUi() {
        withCallPermissions { /* permissions are now available for native SIP calling */ }
    }

    fun requestDefaultDialerFromUi() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roles = getSystemService(RoleManager::class.java)
                if (roles.isRoleAvailable(RoleManager.ROLE_DIALER) && !roles.isRoleHeld(RoleManager.ROLE_DIALER)) {
                    startActivity(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                }
            } else {
                @Suppress("DEPRECATION")
                startActivity(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(
                    TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                    packageName
                ))
            }
        } catch (_: Throwable) {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    private fun withCallPermissions(action: () -> Unit) {
        val missing = buildList {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) action() else {
            afterPermissions = action
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hydrateSipUi() {
        val account = SipAccountStore(this).activeAccount()
        val accountObject = account?.let {
            "{username:${js(it.username)},server:${js(it.domain.substringBefore(':'))},port:${js(it.domain.substringAfter(':', "5060"))},transport:${js(it.transport)}}"
        } ?: "null"
        val online = CoreManager.lastRegState == RegistrationState.Ok
        sendToUi("callerXHydrateSip($accountObject,$online)")
    }

    private fun showIncoming(name: String, number: String) {
        if (!pageReady) {
            pendingIncoming = name to number
            return
        }
        sendToUi("callerXShowIncoming(${js(name)},${js(number)})")
    }

    override fun onRegistrationStateChanged(state: RegistrationState, message: String) {
        sendToUi("callerXSetSipStatus(${js(if (state == RegistrationState.Ok) "ok" else "offline")})")
    }

    override fun onCallStateChanged(call: Call, state: Call.State, message: String) {
        when (state) {
            Call.State.IncomingReceived -> {
                showIncoming(call.remoteAddress.displayName.orEmpty(), call.remoteAddress.username ?: call.remoteAddress.asStringUriOnly())
            }
            Call.State.Connected, Call.State.StreamsRunning -> sendToUi("callerXCallConnected()")
            Call.State.End, Call.State.Error, Call.State.Released -> sendToUi("callerXCallEnded()")
            else -> Unit
        }
    }

    override fun onBackPressed() {
        sendToUi("go('s-calls')")
    }

    private fun sendToUi(script: String) {
        if (!pageReady || isFinishing || isDestroyed) return
        runOnUiThread { webView.evaluateJavascript("javascript:$script", null) }
    }

    private fun js(value: String): String = JSONObject.quote(value)
}
