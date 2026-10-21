package com.allnetworktools.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.allnetworktools.R
import com.allnetworktools.util.S

/** A GitHub release newer than the installed version. */
data class UpdateInfo(
    val version: String,
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    /** Hex SHA-256 announced by GitHub for the asset, when available. */
    val sha256: String?,
)

/** One published release, for the changelog. */
data class ReleaseNote(val tag: String, val publishedAt: String, val notes: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAtMs: Long) : UpdateState
    /** A newer release exists; nothing is downloaded until the user asks for it. */
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState

    /** The APK is downloaded; Android must be allowed to install apps from this one. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/** Compares dotted versions ("0.10" > "0.9"); a leading "v" and any suffix after "-" are ignored. */
fun isNewerVersion(remote: String, local: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    val a = parts(remote)
    val b = parts(local)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/**
 * Looks for a newer release of the app on GitHub, downloads its APK and hands it to the system
 * installer, all without leaving the app. Android still asks the user to confirm the install.
 */
open class AppUpdater(
    private val context: Context,
    private val currentVersion: String,
    private val repo: String = "ToftMalone/All-Radio-Tools",
) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    open val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Newer release found by the last check, kept after « Plus tard » so the settings can still offer it. */
    private val _latest = MutableStateFlow<UpdateInfo?>(null)
    open val latest: StateFlow<UpdateInfo?> = _latest.asStateFlow()

    val lastCheckMs: Long get() = prefs.getLong("last_check", 0L)

    /** Parses the JSON of "releases/latest"; null when it has no APK asset or is not newer. */
    fun parse(json: String): UpdateInfo? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.getString("tag_name")
        val assets = o.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk", true) } ?: return null
        val url = apk.getString("browser_download_url")
        // Only ever download from this repository's releases.
        if (!url.startsWith("https://github.com/$repo/releases/download/")) return null
        return UpdateInfo(
            version = tag.removePrefix("v"), tag = tag, notes = o.optString("body"), apkUrl = url, sizeBytes = apk.optLong("size"),
            sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
        ).takeIf { isNewerVersion(tag, currentVersion) }
    }

    /** Parses the JSON of "releases": published (non-draft, non-prerelease) ones, newest first. */
    fun parseReleases(json: String): List<ReleaseNote> {
        val a = org.json.JSONArray(json)
        return (0 until a.length()).map { a.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
            .map { ReleaseNote(it.getString("tag_name"), it.optString("published_at"), it.optString("body")) }
            .sortedWith { x, y -> if (isNewerVersion(x.tag, y.tag)) -1 else if (isNewerVersion(y.tag, x.tag)) 1 else 0 }
    }

    /** Release notes of every published version, as written on GitHub. */
    open suspend fun releases(): List<ReleaseNote> = withContext(Dispatchers.IO) {
        val c = URL("https://api.github.com/repos/$repo/releases?per_page=50").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "AllRadioTools/$currentVersion")
        try {
            if (c.responseCode != 200) throw java.io.IOException(S(R.string.github_answered, c.responseCode))
            parseReleases(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    /** Asks GitHub for the latest release. With [autoInstall] a newer one is downloaded and installed right away. */
    open suspend fun check(autoInstall: Boolean, force: Boolean = false) {
        val busy = _state.value
        if (busy is UpdateState.Checking || busy is UpdateState.Downloading || busy is UpdateState.Installing) return
        if (!force && System.currentTimeMillis() - lastCheckMs < CheckEveryMs) return
        _state.value = UpdateState.Checking
        val info = try {
            withContext(Dispatchers.IO) {
                val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "AllRadioTools/$currentVersion")
                try {
                    when (c.responseCode) {
                        404 -> null // no release published yet
                        200 -> parse(c.inputStream.bufferedReader().readText())
                        else -> throw java.io.IOException(S(R.string.github_answered, c.responseCode))
                    }
                } finally {
                    c.disconnect()
                }
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(S(R.string.check_failed, e.message ?: S(R.string.network_unavailable)))
            return
        }
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        _latest.value = info
        if (info == null) {
            _state.value = UpdateState.UpToDate(System.currentTimeMillis())
        } else {
            if (autoInstall) {
                _state.value = UpdateState.Downloading(info, 0f)
                downloadAndInstall(info)
            } else {
                _state.value = UpdateState.Available(info)
            }
        }
    }

    /** Downloads the APK (verifying its SHA-256 when GitHub announced one) then starts the install. */
    open suspend fun downloadAndInstall(info: UpdateInfo) {
        val file = try {
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
                val out = File(dir, "AllRadioTools-${info.tag}.apk")
                val c = URL(info.apkUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", "AllRadioTools/$currentVersion")
                val digest = MessageDigest.getInstance("SHA-256")
                try {
                    if (c.responseCode != 200) throw java.io.IOException(S(R.string.download_refused, c.responseCode))
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                    c.inputStream.use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastEmit = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n); digest.update(buf, 0, n); done += n
                                val now = System.nanoTime()
                                if (total > 0 && now - lastEmit > 100_000_000L) {
                                    lastEmit = now; _state.value = UpdateState.Downloading(info, (done.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (info.sha256 != null && !info.sha256.equals(hex, true)) {
                    out.delete(); throw java.io.IOException(S(R.string.wrong_sha_256_fingerprint_file_corrupt))
                }
                out
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(S(R.string.download_failed, e.message ?: S(R.string.network_error)), info)
            return
        }
        pending = file to info
        install()
    }

    private var pending: Pair<File, UpdateInfo>? = null

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows this app to install packages. */
    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Installs the downloaded APK, or asks for the permission it needs first. Safe to call again after granting it. */
    open suspend fun install() {
        val (file, info) = pending ?: return
        if (!canInstall()) {
            _state.value = UpdateState.NeedsPermission(info); return
        }
        _state.value = UpdateState.Installing(info)
        try {
            withContext(Dispatchers.IO) {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    file.inputStream().use { input ->
                        session.openWrite("update.apk", 0, file.length()).use { out ->
                            input.copyTo(out); session.fsync(out)
                        }
                    }
                    val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                    val pi = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(pi.intentSender)
                }
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(S(R.string.installation_failed, e.message), info)
        }
    }

    internal fun onInstallStatus(status: Int, message: String?) {
        val info = pending?.second
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Failed(S(R.string.installation_cancelled), info)
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Failed(S(R.string.signature_differs_from_the_installed_version), info)
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.Failed(S(R.string.not_enough_storage), info)
            else -> UpdateState.Failed(message ?: S(R.string.installation_failed_code, status), info)
        }
    }

    /** A failed check at launch (no network) is not worth a banner; failed downloads and installs still are. */
    fun forgetFailedCheck() {
        val s = _state.value
        if (s is UpdateState.Failed && s.info == null) _state.value = UpdateState.Idle
    }

    /** « Plus tard » : hides the proposal until the next launch or the next manual check. */
    fun postpone() {
        if (_state.value is UpdateState.Available) _state.value = UpdateState.Idle
    }

    /** Shows again the proposal for the release found at launch. */
    fun reoffer() {
        val info = _latest.value ?: return
        val s = _state.value
        if (s !is UpdateState.Downloading && s !is UpdateState.Installing) _state.value = UpdateState.Available(info)
    }

    /** Test hook: shows the proposal for [info]. */
    internal fun offerForTest(info: UpdateInfo) { _latest.value = info; _state.value = UpdateState.Available(info) }

    fun dismiss() {
        if (_state.value is UpdateState.Failed || _state.value is UpdateState.UpToDate) _state.value = UpdateState.Idle
    }

    companion object {
        const val CheckEveryMs = 6 * 3_600_000L
    }
}

/** Receives the installer's progress: shows the system confirmation when needed, reports the result. */
class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val app = context.applicationContext as? com.allnetworktools.AntApplication ?: return
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        app.updater.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
