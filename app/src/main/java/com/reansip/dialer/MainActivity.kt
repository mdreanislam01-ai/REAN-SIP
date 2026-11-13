package com.reansip.dialer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.reansip.dialer.core.CoreManager
import com.reansip.dialer.core.ReanCoreService
import com.reansip.dialer.data.CallLogStore
import com.reansip.dialer.data.RecentCall
import com.reansip.dialer.data.SipAccount
import com.reansip.dialer.data.SipAccountStore
import com.reansip.dialer.databinding.ActivityMainBinding
import com.reansip.dialer.ui.accounts.AccountEditActivity
import com.reansip.dialer.ui.call.CallActivity
import org.linphone.core.Call
import org.linphone.core.RegistrationState
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity(), CoreManager.Events {

    private lateinit var binding: ActivityMainBinding
    private val accountStore by lazy { SipAccountStore(this) }

    private val permissionRequester = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            startCoreServiceIfAllowed()
        } else {
            Toast.makeText(this, R.string.permission_microphone, Toast.LENGTH_LONG).show()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            grants[Manifest.permission.POST_NOTIFICATIONS] == false
        ) {
            Toast.makeText(this, R.string.permission_notifications, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindUi()
        CoreManager.ensureCore(this)
        requestCallPermissionsIfNeeded()
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        CoreManager.addListener(this)
        refreshAll()
        startCoreServiceIfAllowed()
    }

    override fun onPause() {
        CoreManager.removeListener(this)
        super.onPause()
    }

    private fun bindUi() = with(binding) {
        buttonAccounts.setOnClickListener { showAccountPicker() }
        buttonAddFirstAccount.setOnClickListener { openAccountEditor() }
        buttonSwitchAccount.setOnClickListener { showAccountPicker() }

        buttonDialpadTab.setOnClickListener { showDialpad(true) }
        buttonRecentsTab.setOnClickListener { showDialpad(false) }

        val digits = mapOf(
            key1 to "1", key2 to "2", key3 to "3",
            key4 to "4", key5 to "5", key6 to "6",
            key7 to "7", key8 to "8", key9 to "9",
            keyStar to "*", key0 to "0", keyHash to "#"
        )
        digits.forEach { (button, digit) ->
            button.setOnClickListener { editNumber.append(digit) }
        }
        key0.setOnLongClickListener {
            if (!editNumber.text.toString().endsWith("+")) editNumber.append("+")
            true
        }
        buttonBackspace.setOnClickListener {
            val t = editNumber.text
            if (t.isNotEmpty()) t.delete(t.length - 1, t.length)
        }
        buttonBackspace.setOnLongClickListener {
            editNumber.text?.clear()
            true
        }
        buttonCall.setOnClickListener { placeCall() }
    }

    private fun requestCallPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) permissionRequester.launch(needed.toTypedArray())
    }

    private fun startCoreServiceIfAllowed() {
        if (accountStore.activeAccount() == null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ReanCoreService.start(this)
        }
    }

    private fun refreshAll() {
        val active = accountStore.activeAccount()
        binding.emptyAccountPanel.visibility = if (active == null) View.VISIBLE else View.GONE
        binding.dialerPanel.visibility = if (active == null) View.GONE else View.VISIBLE
        binding.activeAccountLabel.text = active?.let { "${it.displayLabel()} • ${it.domain}" }.orEmpty()
        binding.buttonSwitchAccount.visibility = if (accountStore.list().size > 1) View.VISIBLE else View.GONE
        updateRegistration(CoreManager.lastRegState, CoreManager.lastRegMessage)
        renderRecents()
    }

    private fun updateRegistration(state: RegistrationState, message: String) = with(binding) {
        when (state) {
            RegistrationState.Ok -> {
                statusLabel.setText(R.string.registered)
                statusDot.setBackgroundResource(R.drawable.bg_status)
            }
            RegistrationState.Progress -> {
                statusLabel.setText(R.string.registration_progress)
                statusDot.setBackgroundResource(R.drawable.bg_status_warn)
            }
            RegistrationState.Failed -> {
                statusLabel.setText(R.string.registration_failed)
                statusDot.setBackgroundResource(R.drawable.bg_status_error)
            }
            else -> {
                statusLabel.setText(R.string.not_registered)
                statusDot.setBackgroundResource(R.drawable.bg_status_warn)
            }
        }
        if (message.isNotBlank() && state == RegistrationState.Failed) {
            activeAccountLabel.text = message
        }
    }

    private fun showDialpad(dialpad: Boolean) = with(binding) {
        dialpadContent.visibility = if (dialpad) View.VISIBLE else View.GONE
        recentsContent.visibility = if (dialpad) View.GONE else View.VISIBLE
        buttonDialpadTab.isEnabled = !dialpad
        buttonRecentsTab.isEnabled = dialpad
        if (!dialpad) renderRecents()
    }

    private fun placeCall() {
        val raw = binding.editNumber.text?.toString().orEmpty().trim()
        if (raw.isEmpty()) {
            binding.editNumber.error = getString(R.string.invalid_number)
            return
        }
        if (accountStore.activeAccount() == null) {
            Toast.makeText(this, R.string.sip_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestCallPermissionsIfNeeded()
            return
        }
        startCoreServiceIfAllowed()
        // applyAccounts is idempotent and ensures an account is loaded before dialing.
        CoreManager.applyAccounts(this)
        if (CoreManager.startCall(this, raw)) {
            startActivity(Intent(this, CallActivity::class.java))
        } else {
            Toast.makeText(this, R.string.sip_not_ready, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAccountPicker() {
        val accounts = accountStore.list()
        if (accounts.isEmpty()) {
            openAccountEditor()
            return
        }
        val labels = accounts.map { a ->
            if (a.id == accountStore.activeId()) "${a.displayLabel()}  ✓" else a.displayLabel()
        }.toTypedArray()
        val checked = accounts.indexOfFirst { it.id == accountStore.activeId() }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.select_account)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                accountStore.setActive(accounts[which].id)
                CoreManager.applyAccounts(this)
                startCoreServiceIfAllowed()
                dialog.dismiss()
                refreshAll()
            }
            .setNegativeButton(R.string.add_account) { _, _ -> openAccountEditor() }
            .setPositiveButton(R.string.edit_account) { _, _ ->
                openAccountEditor(accounts[checked])
            }
            .show()
    }

    private fun openAccountEditor(account: SipAccount? = null) {
        startActivity(Intent(this, AccountEditActivity::class.java).apply {
            if (account != null) putExtra(AccountEditActivity.EXTRA_ACCOUNT_ID, account.id)
        })
    }

    private fun renderRecents() {
        val container = binding.recentsList
        container.removeAllViews()
        val history = CallLogStore(this).list()
        if (history.isEmpty()) {
            val empty = TextView(this).apply {
                text = "${getString(R.string.no_recents)}\n${getString(R.string.no_recents_body)}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                setPadding(24, 90, 24, 90)
            }
            container.addView(empty, LinearLayout.LayoutParams(-1, -2))
            return
        }
        history.forEach { entry -> container.addView(recentRow(entry)) }
        val clear = TextView(this).apply {
            text = getString(R.string.clear_history)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.danger))
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(16, 28, 16, 20)
            setOnClickListener { confirmClearHistory() }
        }
        container.addView(clear, LinearLayout.LayoutParams(-1, -2))
    }

    private fun recentRow(entry: RecentCall): View {
        val card = MaterialCardView(this).apply {
            radius = 20f
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.surface_card))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                binding.editNumber.setText(entry.number)
                showDialpad(true)
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(18, 14, 18, 14)
        }
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val name = TextView(this).apply {
            text = entry.displayName.ifBlank { entry.number }
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 16f
            maxLines = 1
        }
        val type = when {
            entry.missed -> getString(R.string.missed_call)
            entry.direction == "in" -> getString(R.string.incoming)
            else -> getString(R.string.outgoing)
        }
        val detail = TextView(this).apply {
            val duration = if (entry.durationSec > 0) " • ${durationText(entry.durationSec)}" else ""
            text = "$type$duration • ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestamp))}"
            setTextColor(ContextCompat.getColor(this@MainActivity, if (entry.missed) R.color.danger else R.color.text_secondary))
            textSize = 12f
            maxLines = 1
        }
        details.addView(name)
        details.addView(detail)
        row.addView(details)
        val number = TextView(this).apply {
            text = if (entry.displayName.isNotBlank()) entry.number else ""
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            textSize = 12f
            maxLines = 1
        }
        row.addView(number)
        card.addView(row)
        return card
    }

    private fun durationText(seconds: Int): String = getString(R.string.duration_format, seconds / 60, seconds % 60)

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_clear_history)
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.yes) { _, _ ->
                CallLogStore(this).clear()
                renderRecents()
                Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onRegistrationStateChanged(state: RegistrationState, message: String) {
        updateRegistration(state, message)
    }

    override fun onRecentsChanged() {
        renderRecents()
    }

    override fun onCallStateChanged(call: Call, state: Call.State, message: String) {
        if (state == Call.State.IncomingReceived) return
        if (state == Call.State.OutgoingInit || state == Call.State.OutgoingProgress || state == Call.State.OutgoingRinging) {
            startActivity(Intent(this, CallActivity::class.java))
        }
    }
}
