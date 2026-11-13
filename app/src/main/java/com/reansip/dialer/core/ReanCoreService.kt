package com.reansip.dialer.core

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.reansip.dialer.MainActivity
import com.reansip.dialer.R
import com.reansip.dialer.ReanDialerApp
import com.reansip.dialer.ui.incoming.IncomingCallActivity
import org.linphone.core.Call
import org.linphone.core.RegistrationState

/**
 * Foreground service that keeps the SIP engine alive so incoming calls ring even
 * when the app is closed, and shows a persistent "active" notification.
 */
class ReanCoreService : Service(), CoreManager.Events {

    companion object {
        private const val TAG = "ReanCoreService"
        const val ACTION_START = "com.reansip.dialer.action.START"
        const val ACTION_STOP = "com.reansip.dialer.action.STOP"

        @Volatile
        private var instance: ReanCoreService? = null

        fun get(): ReanCoreService? = instance

        fun start(context: Context) {
            try {
                val i = Intent(context, ReanCoreService::class.java).setAction(ACTION_START)
                ContextCompat.startForegroundService(context, i)
            } catch (t: Throwable) {
                Log.w(TAG, "startForegroundService failed", t)
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, ReanCoreService::class.java).setAction(ACTION_STOP))
            } catch (t: Throwable) {
                Log.w(TAG, "stop service failed", t)
            }
        }
    }

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        CoreManager.ensureCore(this)
        CoreManager.addListener(this)
        CoreManager.applyAccounts(this)
        goForeground(regStatusText())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground(regStatusText())
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        stopRinging()
        releaseWakeLock()
        CoreManager.removeListener(this)
        CoreManager.shutdown()
        super.onDestroy()
    }

    // ---- Registration events ----------------------------------------------------

    override fun onRegistrationStateChanged(state: RegistrationState, message: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(ReanDialerApp.NOTIF_ID_SERVICE, buildServiceNotification(regStatusText()))
    }

    private fun regStatusText(): String = when (CoreManager.lastRegState) {
        RegistrationState.Ok -> getString(R.string.reg_ok)
        RegistrationState.Progress -> getString(R.string.reg_registering)
        RegistrationState.Failed -> getString(R.string.reg_failed)
        else -> getString(R.string.service_notification_text)
    }

    // ---- Incoming / active call hooks (called from CoreManager) -----------------

    fun onIncomingCall(call: Call) {
        acquireWakeLock(45_000)
        startRinging()

        val number = call.remoteAddress.username ?: call.remoteAddress.asStringUriOnly()
        val name = call.remoteAddress.displayName ?: ""

        val fullScreen = Intent(this, IncomingCallActivity::class.java).apply {
            putExtra(IncomingCallActivity.EXTRA_NUMBER, number)
            putExtra(IncomingCallActivity.EXTRA_NAME, name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 0, fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n: Notification = NotificationCompat.Builder(this, ReanDialerApp.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.incoming_call))
            .setContentText(if (name.isNotBlank()) "$name • $number" else number)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(ReanDialerApp.NOTIF_ID_INCOMING, n)
    }

    /** Called when a call (incoming or outgoing) becomes active. */
    fun onCallActive() {
        stopRinging()
        cancelIncomingNotification()
    }

    fun onAllCallsEnded() {
        stopRinging()
        cancelIncomingNotification()
        releaseWakeLock()
        CoreManager.setMicMuted(false)
    }

    /** The user pressed Answer in the incoming-call UI. */
    fun onCallAnsweredFromUi() {
        stopRinging()
        cancelIncomingNotification()
    }

    private fun cancelIncomingNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.cancel(ReanDialerApp.NOTIF_ID_INCOMING)
    }

    // ---- Ringtone / vibration ----------------------------------------------------

    @Synchronized
    private fun startRinging() {
        if (ringing) return
        ringing = true
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ringtone", t)
        }
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            vibrator = v
            val pattern = longArrayOf(0, 800, 700)
            v.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } catch (t: Throwable) {
            Log.w(TAG, "vibrate", t)
        }
    }

    @Synchronized
    fun stopRinging() {
        ringing = false
        try { ringtone?.stop() } catch (t: Throwable) { Log.w(TAG, "stop ringtone", t) }
        try { vibrator?.cancel() } catch (t: Throwable) { Log.w(TAG, "stop vibrate", t) }
        ringtone = null
    }

    // ---- Wake lock -----------------------------------------------------------------

    private fun acquireWakeLock(timeoutMs: Long) {
        try {
            releaseWakeLock()
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reansip:incoming").apply {
                acquire(timeoutMs)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wakelock", t)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (t: Throwable) {
            Log.w(TAG, "release wakelock", t)
        }
        wakeLock = null
    }

    // ---- Foreground notification ------------------------------------------------------

    private fun goForeground(text: String) {
        ServiceCompat.startForeground(
            this,
            ReanDialerApp.NOTIF_ID_SERVICE,
            buildServiceNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    private fun buildServiceNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ReanDialerApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
