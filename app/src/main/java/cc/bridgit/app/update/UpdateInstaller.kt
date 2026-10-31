package cc.bridgit.app.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.update.UpdateLogic.Problem
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext
import cc.bridgit.app.core.Compat

/**
 * Network, file and PackageManager side of the update flow (spec 12.14). Ported in concept
 * from Spotka's AppUpdateInstaller, with HttpURLConnection instead of OkHttp and stricter
 * trust rules (UPD-03): no redirects, signing certificate must equal the installed app's.
 *
 * Network and disk work runs on Dispatchers.IO. Every check, download and refusal is a
 * `diagnostic` log entry with the host only, the versions and the result (UPD-07).
 */
object UpdateInstaller {
    private const val DIR = "updates"
    private const val STALE_MS = 60L * 60 * 1000
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val APK_MIME = "application/vnd.android.package-archive"

    sealed class Check {
        data class UpToDate(val manifest: UpdateLogic.Manifest) : Check()
        data class Newer(val manifest: UpdateLogic.Manifest) : Check()
        /** The installed build is newer than the published one (a local release build). */
        data class LocalAhead(val manifest: UpdateLogic.Manifest) : Check()
        data class Refused(val problem: Problem) : Check()
    }

    sealed class Download {
        data class Ready(val file: File, val manifest: UpdateLogic.Manifest) : Download()
        data class Refused(val problem: Problem) : Download()
    }

    private class Refusal(val problem: Problem, val detail: String) : IOException(detail)

    // ---- Check ----

    suspend fun check(app: BridgeApp): Check = withContext(Dispatchers.IO) {
        val installed = installedVersionCode(app)
        val host = UpdateLogic.hostOf(UpdateLogic.MANIFEST_URL)
        try {
            val body = fetchManifest(userAgent(app))
            when (val p = UpdateLogic.parseManifest(body, app.packageName)) {
                is UpdateLogic.Parsed.Refused -> {
                    log(app, "Update check refused: ${p.problem.name.lowercase()} (${p.detail}) from $host", "warn")
                    Check.Refused(p.problem)
                }
                is UpdateLogic.Parsed.Ok -> {
                    val m = p.manifest
                    val result = when (UpdateLogic.compare(installed, m.versionCode)) {
                        UpdateLogic.Compare.NEWER -> Check.Newer(m)
                        UpdateLogic.Compare.CURRENT -> Check.UpToDate(m)
                        UpdateLogic.Compare.LOCAL_AHEAD -> Check.LocalAhead(m)
                        UpdateLogic.Compare.UNCOMPARABLE -> Check.Refused(Problem.BAD_MANIFEST)
                    }
                    val word = when (result) {
                        is Check.Newer -> "newer version available"
                        is Check.UpToDate -> "up to date"
                        is Check.LocalAhead -> "installed build is ahead"
                        is Check.Refused -> "versions not comparable"
                    }
                    log(app, "Update check from $host: installed $installed, published ${m.versionName} (${m.versionCode}): $word")
                    result
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Refusal) {
            log(app, "Update check refused: ${e.problem.name.lowercase()} (${e.detail}) from $host", "warn")
            Check.Refused(e.problem)
        } catch (e: Exception) {
            log(app, "Update check failed: ${e.javaClass.simpleName} from $host", "warn")
            Check.Refused(Problem.NETWORK)
        }
    }

    /** GET latest.json, with one retry on a transient status (408, 429, 5xx). */
    private suspend fun fetchManifest(ua: String): String {
        var attempt = 0
        while (true) {
            attempt++
            val conn = open(UpdateLogic.MANIFEST_URL, "application/json", ua)
            try {
                val code = conn.responseCode
                if (code != 200) {
                    if (attempt == 1 && UpdateLogic.isTransientHttpStatus(code)) { delay(2_000); continue }
                    throw Refusal(UpdateLogic.problemForStatus(code), "HTTP $code")
                }
                val declared = conn.contentLengthLong
                if (declared > UpdateLogic.MAX_MANIFEST_BYTES) throw Refusal(Problem.BAD_MANIFEST, "manifest too large")
                return conn.inputStream.use { readCapped(it, UpdateLogic.MAX_MANIFEST_BYTES) }.toString(Charsets.UTF_8)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun readCapped(input: InputStream, cap: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > cap) throw Refusal(Problem.BAD_MANIFEST, "manifest too large")
        }
        return out.toByteArray()
    }

    /** HTTPS only, no redirects, timeouts set. Refuses any URL that fails the trust rules. */
    private fun open(url: String, accept: String, ua: String): HttpsURLConnection {
        val safe = if (url.endsWith(".apk")) UpdateLogic.isSafeApkUrl(url) else UpdateLogic.isSafeManifestUrl(url)
        if (!safe) throw Refusal(Problem.UNSAFE_URL, "url host ${UpdateLogic.hostOf(url)}")
        val conn = URL(url).openConnection() as? HttpsURLConnection
            ?: throw Refusal(Problem.UNSAFE_URL, "not https")
        conn.instanceFollowRedirects = false
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.useCaches = false
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", ua)
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Cache-Control", "no-cache")
        return conn
    }

    // ---- Download and verify ----

    /**
     * Streams the APK to cacheDir/updates/<unique>.apk.part, checks size and SHA-256, renames,
     * then validates the archive against the manifest and the installed app (UPD-03).
     * [onProgress] gets 0..100, or -1 when the length is unknown. Cancellation deletes the files.
     */
    suspend fun download(app: BridgeApp, m: UpdateLogic.Manifest, onProgress: (Int) -> Unit): Download =
        withContext(Dispatchers.IO) {
            val host = UpdateLogic.hostOf(m.url)
            val dir = cacheDir(app)
            val dest = File(dir, "bridget-${m.versionCode}-${UUID.randomUUID()}.apk")
            val part = File(dir, dest.name + ".part")
            log(app, "Update download started from $host: ${m.versionName} (${m.versionCode}), ${m.sizeBytes} bytes")
            try {
                if (!UpdateLogic.isSafeApkUrl(m.url) || !UpdateLogic.isValidSha256(m.sha256))
                    throw Refusal(Problem.UNSAFE_URL, "url host $host")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                val conn = open(m.url, "$APK_MIME,application/octet-stream", userAgent(app))
                try {
                    val code = conn.responseCode
                    if (code != 200) throw Refusal(UpdateLogic.problemForStatus(code), "HTTP $code")
                    val declared = conn.contentLengthLong
                    if (declared > UpdateLogic.MAX_APK_BYTES) throw Refusal(Problem.TOO_LARGE, "Content-Length $declared")
                    if (declared >= 0 && declared != m.sizeBytes) throw Refusal(Problem.SIZE_MISMATCH, "Content-Length $declared")
                    val expected = m.sizeBytes
                    var last = -2
                    conn.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > UpdateLogic.MAX_APK_BYTES) throw Refusal(Problem.TOO_LARGE, "over 100 MB")
                                if (total > expected) throw Refusal(Problem.SIZE_MISMATCH, "longer than size_bytes")
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                val pct = ((total * 100) / expected).toInt().coerceIn(0, 100)
                                if (pct != last) { last = pct; onProgress(pct) }
                            }
                            out.fd.sync()
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                coroutineContext.ensureActive()
                if (total != m.sizeBytes) throw Refusal(Problem.SIZE_MISMATCH, "got $total bytes")
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                if (got != m.sha256.lowercase()) throw Refusal(Problem.CHECKSUM, "sha256 mismatch")
                if (!part.renameTo(dest)) throw Refusal(Problem.STORAGE, "rename failed")
                verifyArchive(app, dest, m)
                log(app, "Update download verified from $host: ${m.versionName} (${m.versionCode}), $total bytes, " +
                    "checksum, package, version and signing certificate match")
                Download.Ready(dest, m)
            } catch (e: CancellationException) {
                part.delete(); dest.delete()
                log(app, "Update download cancelled: ${m.versionName} (${m.versionCode})")
                throw e
            } catch (e: Refusal) {
                part.delete(); dest.delete()
                log(app, "Update refused: ${e.problem.name.lowercase()} (${e.detail}), ${m.versionName} (${m.versionCode}) from $host", "warn")
                Download.Refused(e.problem)
            } catch (e: Exception) {
                part.delete(); dest.delete()
                log(app, "Update download failed: ${e.javaClass.simpleName}, ${m.versionName} (${m.versionCode}) from $host", "warn")
                Download.Refused(if (e is IOException) Problem.NETWORK else Problem.STORAGE)
            }
        }

    /** Package, versionCode (equal to the manifest, above installed) and signing certificates (UPD-03). */
    private fun verifyArchive(ctx: Context, file: File, m: UpdateLogic.Manifest) {
        val pm = ctx.packageManager
        val archive = Compat.packageArchiveInfo(pm, file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw Refusal(Problem.NOT_AN_APK, "unreadable archive")
        val installed = Compat.packageInfo(pm, ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        UpdateLogic.checkArchive(archive.packageName, archive.longVersionCode, m, ctx.packageName, installed.longVersionCode)
            ?.let { throw Refusal(it, "archive ${archive.packageName} ${archive.longVersionCode}") }
        val a = certDigests(archive)
        if (a.isEmpty()) throw Refusal(Problem.UNSIGNED, "no signing certificate")
        if (!UpdateLogic.certSetsMatch(a, certDigests(installed))) throw Refusal(Problem.CERT_MISMATCH, "signing certificate differs")
    }

    private fun certDigests(info: PackageInfo): List<String> {
        // apkContentsSigners: the certificates the APK is signed with now (one, or several for multi-signer APKs).
        val certs = info.signingInfo?.apkContentsSigners ?: return emptyList()
        return certs.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    // ---- Install ----

    fun canRequestInstalls(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Android's "Install unknown apps" page for Bridgit. */
    fun unknownSourcesIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    /** Hands the verified file to Android's package installer, which always asks to confirm (UPD-04). */
    fun installIntent(ctx: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            clipData = ClipData.newRawUri("apk", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun logInstallHandoff(app: BridgeApp, m: UpdateLogic.Manifest) =
        log(app, "Update handed to Android's installer: ${m.versionName} (${m.versionCode})")

    fun logInstallBlocked(app: BridgeApp) =
        log(app, "Update waiting: Install unknown apps is off for Bridgit; opened Android's page", "warn")

    // ---- Housekeeping ----

    /** cacheDir/updates, with files older than an hour removed (UPD-05). */
    private fun cacheDir(ctx: Context): File {
        val dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - STALE_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        return dir
    }

    /** Called on app start-up paths (the dialog) so stale downloads don't linger. */
    fun cleanStale(ctx: Context) { runCatching { cacheDir(ctx) } }

    private fun userAgent(ctx: Context): String = UpdateLogic.userAgent(
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?",
        android.os.Build.VERSION.RELEASE ?: "?")

    private fun installedVersionCode(ctx: Context): Long =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L)

    private fun log(app: BridgeApp, message: String, level: String = "info") =
        runCatching { app.log.system("diagnostic", "service", message, level) }
}
