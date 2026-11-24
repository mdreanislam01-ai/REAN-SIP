package com.callerx.app

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.DateFormat
import java.util.Date

/**
 * Installs an uncaught-exception handler so a crash leaves a readable log inside
 * the app (files/crash-log.txt). The log can be shared from Settings → Send crash log,
 * which keeps debugging possible without any crash-reporting service or account.
 */
class CallerXApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val file = File(filesDir, "crash-log.txt")
                val stamp = DateFormat.getDateTimeInstance().format(Date())
                file.writeText("CallerX crash · $stamp\nthread: ${thread.name}\n\n${sw}\n")
            } catch (_: Exception) { }
            try { previous?.uncaughtException(thread, error) } catch (_: Exception) { }
        }
    }
}
