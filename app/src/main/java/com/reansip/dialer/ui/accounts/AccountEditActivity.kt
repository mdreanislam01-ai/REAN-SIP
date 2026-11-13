package com.reansip.dialer.ui.accounts

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.reansip.dialer.R
import com.reansip.dialer.core.CoreManager
import com.reansip.dialer.core.ReanCoreService
import com.reansip.dialer.data.SipAccount
import com.reansip.dialer.data.SipAccountStore
import com.reansip.dialer.databinding.ActivityAccountEditBinding

class AccountEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACCOUNT_ID = "account_id"
    }

    private lateinit var binding: ActivityAccountEditBinding
    private val store by lazy { SipAccountStore(this) }
    private var existing: SipAccount? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        existing = intent.getLongExtra(EXTRA_ACCOUNT_ID, -1L)
            .takeIf { it > 0L }
            ?.let { id -> store.list().find { it.id == id } }
        setupUi()
    }

    private fun setupUi() = with(binding) {
        buttonBack.setOnClickListener { finish() }
        spinnerTransport.setAdapter(ArrayAdapter(this@AccountEditActivity, android.R.layout.simple_list_item_1, arrayOf("UDP", "TCP", "TLS")))

        existing?.let { a ->
            titleAccount.setText(R.string.edit_account)
            editLabel.setText(a.label)
            editUsername.setText(a.username)
            editPassword.setText(a.password)
            editDomain.setText(a.domain)
            spinnerTransport.setText(a.transport, false)
            editDisplayName.setText(a.displayName)
            switchRegistration.isChecked = a.registerEnabled
            buttonDeleteAccount.visibility = android.view.View.VISIBLE
        } ?: spinnerTransport.setText("UDP", false)

        buttonSaveAccount.setOnClickListener { saveAccount() }
        buttonDeleteAccount.setOnClickListener { existing?.let { confirmDelete(it) } }
    }

    private fun saveAccount() = with(binding) {
        val username = editUsername.text?.toString()?.trim().orEmpty()
        val domain = editDomain.text?.toString()?.trim().orEmpty()
        if (username.isBlank() || domain.isBlank()) {
            Toast.makeText(this@AccountEditActivity, R.string.enter_account_details, Toast.LENGTH_SHORT).show()
            return
        }
        val account = SipAccount(
            id = existing?.id ?: System.currentTimeMillis(),
            label = editLabel.text?.toString()?.trim().orEmpty(),
            username = username,
            password = editPassword.text?.toString().orEmpty(),
            domain = domain,
            transport = spinnerTransport.text?.toString()?.uppercase()?.ifBlank { "UDP" } ?: "UDP",
            displayName = editDisplayName.text?.toString()?.trim().orEmpty(),
            registerEnabled = switchRegistration.isChecked
        )
        store.upsert(account)
        CoreManager.applyAccounts(this@AccountEditActivity)
        ReanCoreService.start(this@AccountEditActivity)
        Toast.makeText(this@AccountEditActivity, R.string.account_saved, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ACCOUNT_ID, account.id))
        finish()
    }

    private fun confirmDelete(account: SipAccount) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_delete_account, account.displayLabel()))
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.yes) { _, _ ->
                store.delete(account.id)
                CoreManager.applyAccounts(this)
                if (store.activeAccount() == null) ReanCoreService.stop(this)
                Toast.makeText(this, R.string.account_deleted, Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
            .show()
    }
}
