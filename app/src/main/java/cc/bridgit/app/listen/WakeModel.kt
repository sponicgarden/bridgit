package cc.bridgit.app.listen

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The offline English speech model for wake words (Vosk small, Apache 2.0, about 40 MB). Not in the APK:
 * the owner downloads it once from Bridgit's wake-word screen, and the zip must match [SHA256] before it's used.
 * This is the feature's only network fetch; audio never goes anywhere.
 */
class WakeModel(private val ctx: Context) {
    sealed class State {
        object Absent : State()
        data class Downloading(val percent: Int) : State()
        object Ready : State()
        data class Failed(val message: String) : State()
    }

    private val dir = File(ctx.filesDir, "wake/$NAME")
    private val _state = MutableStateFlow<State>(if (installed()) State.Ready else State.Absent)
    val state: StateFlow<State> get() = _state
    @Volatile private var model: Model? = null

    /** Space the unpacked model takes on the phone. */
    fun bytesOnDisk(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun installed() = File(dir, "am/final.mdl").exists() && File(dir, "graph/HCLr.fst").exists()
    val ready get() = _state.value == State.Ready

    /** Loaded once and kept; loading takes a second or two. */
    @Synchronized fun load(): Model = model ?: Model(dir.path).also { model = it }

    /** Whether the model knows [word] (vosk_model_find_word; not wrapped by the Java API). */
    fun knows(word: String): Boolean = runCatching {
        com.sun.jna.Function.getFunction("vosk", "vosk_model_find_word").invokeInt(arrayOf(load(), word)) >= 0
    }.getOrDefault(true)

    suspend fun download() = withContext(Dispatchers.IO) {
        if (_state.value is State.Downloading) return@withContext
        _state.value = State.Downloading(0)
        val zip = File(ctx.cacheDir, "wake-model.zip")
        val tmp = File(ctx.filesDir, "wake/tmp")
        try {
            val conn = URL(URL_ZIP).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            if (conn.responseCode != 200) throw IllegalStateException("The model server answered ${conn.responseCode}.")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: SIZE
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input -> zip.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                    if (done > SIZE * 2) throw IllegalStateException("The download is larger than expected.")
                    _state.value = State.Downloading((done * 100 / total).toInt().coerceIn(0, 99))
                }
            } }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != SHA256) throw IllegalStateException("The download didn't match the expected model, so it wasn't used.")
            tmp.deleteRecursively(); tmp.mkdirs()
            ZipInputStream(zip.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val target = File(tmp, e.name)
                    if (!target.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) throw IllegalStateException("Bad entry in the model zip.")
                    if (e.isDirectory) target.mkdirs() else { target.parentFile?.mkdirs(); target.outputStream().use { z.copyTo(it) } }
                }
            }
            dir.deleteRecursively(); dir.parentFile?.mkdirs()
            if (!File(tmp, NAME).renameTo(dir)) throw IllegalStateException("Couldn't install the model.")
            _state.value = State.Ready
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
        } finally { zip.delete(); tmp.deleteRecursively() }
    }

    @Synchronized fun delete() {
        model?.close(); model = null
        dir.deleteRecursively()
        _state.value = State.Absent
    }

    companion object {
        const val NAME = "vosk-model-small-en-us-0.15"
        const val LABEL = "Vosk small English (US) 0.15"
        const val URL_ZIP = "https://alphacephei.com/vosk/models/$NAME.zip"
        const val SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
        const val SIZE = 41_205_931L
    }
}
