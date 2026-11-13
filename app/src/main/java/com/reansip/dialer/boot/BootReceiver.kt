package com.reansip.dialer.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reansip.dialer.core.ReanCoreService
import com.reansip.dialer.data.SipAccountStore

/** Restarts the SIP background service after a device reboot, if an account exists. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (SipAccountStore(context).activeAccount() != null) {
                ReanCoreService.start(context)
            }
        }
    }
}
