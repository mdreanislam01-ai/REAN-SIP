package com.callerx.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app updater.
 *
 * Checks the public GitHub release feed for a newer `callerx-vX.Y.Z` release, downloads the APK
 * that matches the phone's ABI, makes sure it is signed with the same key as the installed app and
 * hands it to the Android package installer. Android always asks the owner to confirm the install
 * for a sideloaded APK — that last tap cannot be removed by an app without a system/Play signature.
 */
data class UpdateInfo(
    val version: String,
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    val pageUrl: String
)

object UpdateChecker {

    private const val REPO = "mdreanislam01-ai/REAN-SIP"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val TAG_PREFIX = "callerx-v"

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (_: Exception) { "0" }

    fun currentVersionCode(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (_: Exception) { 0L }

    /** Compares dotted versions: "1.5.9" < "1.5.10". */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.trim().removePrefix(TAG_PREFIX).split('.', '-', '+')
        val b = current.trim().split('.', '-', '+')
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val y = b.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            if (x != y) return x > y
        }
        return false
    }

    private fun http(url: String, timeoutMs: Int = 15000): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "CallerX-Android")
        return conn
    }

    /** Returns the newest release when it is newer than the installed build, otherwise null. */
    fun check(context: Context): UpdateInfo? {
        var conn: HttpURLConnection? = null
        return try {
            conn = http(API_LATEST)
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val tag = root.optString("tag_name")
            if (!tag.startsWith(TAG_PREFIX)) return null
            val version = tag.removePrefix(TAG_PREFIX)
            if (!isNewer(version, currentVersion(context))) return null
            val assets = root.optJSONArray("assets") ?: return null
            val abis = Build.SUPPORTED_ABIS.map { it.lowercase() }
            val wantArm64 = abis.any { it.contains("arm64") }
            fun find(predicate: (String) -> Boolean): JSONObject? {
                for (i in 0 until assets.length()) {
                    val o = assets.optJSONObject(i) ?: continue
                    if (predicate(o.optString("name"))) return o
                }
                return null
            }
            val asset = find { n -> wantArm64 && n.contains("arm64-v8a") && n.endsWith(".apk") }
                ?: find { n -> n == "app-debug.apk" }
                ?: find { n -> n.endsWith(".apk") }
                ?: return null
            UpdateInfo(
                version = version,
                tag = tag,
                notes = root.optString("body").take(1500),
                apkUrl = asset.optString("browser_download_url"),
                apkName = asset.optString("name"),
                apkSize = asset.optLong("size"),
                pageUrl = root.optString("html_url")
            )
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }

    fun updateDir(context: Context): File = File(context.cacheDir, "updates").apply { mkdirs() }

    /** Downloads the APK and reports 0f..1f progress (or -1f when the size is unknown). */
    fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File {
        val target = File(updateDir(context), "callerx-${info.version}.apk")
        if (target.exists() && target.length() == info.apkSize && info.apkSize > 0) { onProgress(1f); return target }
        val tmp = File(target.absolutePath + ".part")
        var conn: HttpURLConnection? = null
        try {
            conn = http(info.apkUrl, 30000)
            conn.connectTimeout = 20000
            if (conn.responseCode !in 200..299) throw IllegalStateException("Download failed (HTTP ${conn.responseCode})")
            val total = if (info.apkSize > 0) info.apkSize else conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (info.apkSize > 0 && tmp.length() != info.apkSize) throw IllegalStateException("Download incomplete (${tmp.length()} of ${info.apkSize} bytes)")
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IllegalStateException("Could not store the downloaded file")
            onProgress(1f)
            return target
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
            if (tmp.exists() && !target.exists()) tmp.delete()
        }
    }

    private fun signingDigest(context: Context, apk: File?): ByteArray? = try {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val info = if (apk == null) pm.getPackageInfo(context.packageName, flags)
        else pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return null
        val cert = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.firstOrNull()
        else @Suppress("DEPRECATION") info.signatures?.firstOrNull()
        cert?.toByteArray()?.let { MessageDigest.getInstance("SHA-256").digest(it) }
    } catch (_: Exception) { null }

    /** Rejects anything that is not our own package, not newer, or signed with another key. */
    fun verify(context: Context, apk: File, info: UpdateInfo): String? {
        if (!apk.exists() || apk.length() < 1024 * 1024) return "The downloaded file looks incomplete."
        val archive = try {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        } catch (_: Exception) { null } ?: return "The downloaded file is not a valid APK."
        if (archive.packageName != context.packageName) return "The downloaded APK belongs to another app."
        val newVersion = archive.versionName ?: ""
        if (!isNewer(newVersion, currentVersion(context))) return "The downloaded APK is not newer than the installed app."
        val installed = signingDigest(context, null) ?: return "Could not read this app's signing certificate."
        val downloaded = signingDigest(context, apk) ?: return "Could not read the downloaded APK's signature."
        if (!installed.contentEquals(downloaded)) return "Signature mismatch — the download was not signed by CallerX's key, so it was blocked."
        return null
    }

    fun canInstall(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 26) context.packageManager.canRequestPackageInstalls() else true
    } catch (_: Exception) { true }

    fun installPermissionIntent(context: Context): Intent = Intent(
        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        android.net.Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Cleans old downloads, keeping the newest one only. */
    fun prune(context: Context) {
        try {
            val dir = updateDir(context)
            val files = dir.listFiles()?.filter { it.extension == "apk" }?.sortedByDescending { it.lastModified() } ?: return
            files.drop(1).forEach { it.delete() }
            dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        } catch (_: Exception) { }
    }
}
