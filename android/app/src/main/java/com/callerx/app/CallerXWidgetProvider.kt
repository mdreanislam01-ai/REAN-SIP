package com.callerx.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget: live SIP status plus three shortcuts (dial pad, speed dial 1,
 * voicemail). Everything it shows comes from the "callerx" preferences file, so the
 * widget keeps working while the Activity is not running.
 */
class CallerXWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            try { appWidgetManager.updateAppWidget(id, buildViews(context)) } catch (_: Exception) { }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        /* Any widget refresh request repaints every instance. */
        if (intent.action == ACTION_REFRESH) refresh(context)
    }

    companion object {
        const val ACTION_REFRESH = "com.callerx.app.WIDGET_REFRESH"

        private fun open(context: Context, extra: String, value: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(extra, value)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.callerx_widget)
            val prefs = context.getSharedPreferences("callerx", Context.MODE_PRIVATE)
            val online = prefs.getBoolean("status:online", false)
            val account = prefs.getString("status:account", "").orEmpty()
            views.setTextViewText(R.id.widget_status, if (online) "SIP ONLINE" else "SIP OFFLINE")
            views.setTextColor(R.id.widget_status, if (online) 0xFF7DD8A8.toInt() else 0xFFF2B8B5.toInt())
            val voicemail = prefs.getString("setting:voicemail", "").orEmpty()
            val speed1 = prefs.getString("speed:1", "").orEmpty()
            val detail = when {
                online && account.isNotBlank() -> "$account · ready to call"
                online -> "Registered · ready to call"
                speed1.isNotBlank() -> "Speed 1: $speed1"
                voicemail.isNotBlank() -> "Voicemail: $voicemail"
                else -> "Tap a shortcut to call"
            }
            views.setTextViewText(R.id.widget_number, detail)
            views.setOnClickPendingIntent(R.id.widget_dial, open(context, "callerx_widget", "dialer", 101))
            views.setOnClickPendingIntent(R.id.widget_speed1, open(context, "callerx_widget", "speed:1", 102))
            views.setOnClickPendingIntent(R.id.widget_voicemail, open(context, "callerx_widget", "voicemail", 103))
            views.setOnClickPendingIntent(R.id.widget_title, open(context, "callerx_widget", "dialer", 104))
            return views
        }

        /** Repaints every widget instance; safe to call when no widget exists. */
        fun refresh(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val component = ComponentName(context, CallerXWidgetProvider::class.java)
                val ids = manager.getAppWidgetIds(component)
                if (ids == null || ids.isEmpty()) return
                val views = buildViews(context)
                manager.updateAppWidget(component, views)
            } catch (_: Exception) { }
        }
    }
}
