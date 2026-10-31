package cc.bridgit.app.capability

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.coroutines.resume
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.Sizing
import cc.bridgit.app.core.Compat

class Encoded(val bytes: ByteArray, val width: Int, val height: Int, val mime: String)

/**
 * Permission names as this Android version knows them. Below Android 13 photos and videos fall under
 * READ_EXTERNAL_STORAGE and notifications need no runtime grant; "Select photos" exists from Android 14.
 * Null means the permission doesn't exist here, so there is nothing to ask for.
 */
object Perms {
    fun platform(p: String): String? = when {
        p == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33 -> null
        p == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED && Build.VERSION.SDK_INT < 34 -> null
        (p == Manifest.permission.READ_MEDIA_IMAGES || p == Manifest.permission.READ_MEDIA_VIDEO) && Build.VERSION.SDK_INT < 33 ->
            Manifest.permission.READ_EXTERNAL_STORAGE
        else -> p
    }

    /** What to pass to a permission request on this Android version. */
    fun forRequest(perms: Array<out String>): Array<String> = perms.mapNotNull(::platform).distinct().toTypedArray()
}

/** Whether [p] is held, by its meaning on this Android version: notifications count as granted before Android 13. */
fun Context.granted(p: String): Boolean {
    val real = Perms.platform(p) ?: return p == Manifest.permission.POST_NOTIFICATIONS
    return ContextCompat.checkSelfPermission(this, real) == PackageManager.PERMISSION_GRANTED
}

fun isoAt(epochMs: Long): String =
    OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
        .truncatedTo(ChronoUnit.SECONDS).toString()

object ImageUtil {
    /**
     * Applies EXIF rotation and resizes per [spec] (API-10). Returns the original
     * bytes untouched when nothing needs to change and [forceJpeg] is false.
     */
    fun prepare(original: ByteArray, mime: String, spec: Sizing.Spec, forceJpeg: Boolean = false): Encoded {
        val exif = runCatching { ExifInterface(original.inputStream()) }.getOrNull()
        val rotation = exif?.rotationDegrees ?: 0
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0) throw ApiError(500, "internal_error", "Could not decode image")
        val (w, h) = if (rotation % 180 != 0) rawH to rawW else rawW to rawH
        val (tw, th) = Sizing.target(w, h, spec)
        val needsJpeg = forceJpeg && mime != "image/jpeg"
        if (spec.original || (tw == w && rotation == 0 && !needsJpeg)) return Encoded(original, w, h, mime)

        val sample = Sizing.sampleSize(maxOf(w, h), maxOf(tw, th))
        val bmp = BitmapFactory.decodeByteArray(original, 0, original.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw ApiError(500, "internal_error", "Could not decode image")
        val m = Matrix()
        if (rotation != 0) m.postRotate(rotation.toFloat())
        val rotatedW = if (rotation % 180 != 0) bmp.height else bmp.width
        val scale = tw.toFloat() / rotatedW
        if (scale < 1f) m.postScale(scale, scale)
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, spec.quality, bos)
        val res = Encoded(bos.toByteArray(), out.width, out.height, "image/jpeg")
        if (out !== bmp) out.recycle()
        bmp.recycle()
        return res
    }
}

class CameraCapture(private val context: Context, private val owner: LifecycleOwner) {

    /** Zoom range of the last camera used, for /v1/capabilities and error messages. */
    @Volatile var lastZoomRange: Pair<Float, Float>? = null; private set

    /**
     * [zoom] (CAP-05): null = the camera's default (1x). Below 1 uses the ultra-wide lens and
     * above about 5 the telephoto on phones whose camera switches lenses by zoom (Pixel).
     */
    suspend fun snapshot(rear: Boolean, flash: Boolean, spec: Sizing.Spec, zoom: Float? = null): Encoded {
        val km = context.getSystemService(KeyguardManager::class.java)
        if (km.isDeviceLocked) throw ApiError(409, "device_locked", "Phone is locked. Unlock it, then retry.")

        val file = File(context.cacheDir, "snap-${System.nanoTime()}.jpg")
        try {
            val provider = withContext(Dispatchers.Main) { ProcessCameraProvider.getInstance(context).await() }
            val selector = if (rear) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            if (!withContext(Dispatchers.Main) { provider.hasCamera(selector) })
                throw ApiError(422, "invalid_parameter", "This phone has no ${if (rear) "rear" else "front"} camera")
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build()
                ).build()
            val camera = try {
                withContext(Dispatchers.Main) { provider.bindToLifecycle(owner, selector, capture) }
            } catch (e: Exception) {
                throw ApiError(409, "sensor_busy", "Camera unavailable: ${e.message}")
            }
            try {
                val zs = withContext(Dispatchers.Main) { camera.cameraInfo.zoomState.value }
                if (zs != null) lastZoomRange = zs.minZoomRatio to zs.maxZoomRatio
                if (zoom != null) {
                    val (min, max) = lastZoomRange ?: (1f to 1f)
                    if (zoom < min - 0.001f || zoom > max + 0.001f) throw ApiError(422, "invalid_parameter",
                        "zoom must be ${"%.1f".format(min)} to ${"%.1f".format(max)} on this ${if (rear) "rear" else "front"} camera",
                        mapOf("min_zoom" to min, "max_zoom" to max))
                    withContext(Dispatchers.Main) { camera.cameraControl.setZoomRatio(zoom).await() }
                    delay(400) // let focus and exposure settle at the new zoom
                }
                // take() yields Result: success, or the capture error. null here means timeout.
                val outcome = withTimeoutOrNull(20_000) { take(capture, file) }
                    ?: throw ApiError(504, "capture_timeout", "Camera did not produce a photo in time")
                outcome.exceptionOrNull()?.let { throw ApiError(409, "sensor_busy", "Capture failed: ${it.message}") }
            } finally {
                // Unbind even when the request was cancelled (client gone), or the camera stays held.
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { provider.unbind(capture) }
            }
            return ImageUtil.prepare(file.readBytes(), "image/jpeg", spec)
        } finally {
            file.delete()
        }
    }

    private suspend fun take(capture: ImageCapture, file: File): Result<Unit> =
        suspendCancellableCoroutine { cont ->
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(r: ImageCapture.OutputFileResults) { if (cont.isActive) cont.resume(Result.success(Unit)) }
                    override fun onError(e: ImageCaptureException) { if (cont.isActive) cont.resume(Result.failure(e)) }
                })
        }

    private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            addListener({ runCatching { get() }.fold({ cont.resume(it) }, { cont.cancel(it) }) },
                ContextCompat.getMainExecutor(context))
        }
}

class MicCapture(private val context: Context) {
    class Clip(val bytes: ByteArray, val durationSeconds: Int, val sampleRate: Int)

    suspend fun record(seconds: Int): Clip {
        val am = context.getSystemService(AudioManager::class.java)
        if (am.activeRecordingConfigurations.isNotEmpty())
            throw ApiError(409, "sensor_busy", "Another app is using the microphone")
        val file = File(context.cacheDir, "clip-${System.nanoTime()}.m4a")
        val rate = 44100
        val rec = Compat.mediaRecorder(context)
        try {
            try {
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioSamplingRate(rate)
                rec.setAudioChannels(1)
                rec.setAudioEncodingBitRate(96_000)
                rec.setOutputFile(file)
                rec.prepare()
                rec.start()
            } catch (e: Exception) {
                throw ApiError(409, "sensor_busy", "Microphone unavailable: ${e.message}")
            }
            delay(seconds * 1000L)
            try { rec.stop() } catch (e: Exception) {
                throw ApiError(504, "capture_timeout", "Recording did not finish cleanly")
            }
            return Clip(file.readBytes(), seconds, rate)
        } finally {
            rec.release()
            file.delete()
        }
    }
}

class Gallery(private val context: Context) {

    /** "full", "selected", or null when no photo permission is held. */
    fun accessMode(): String? = when {
        context.granted(Manifest.permission.READ_MEDIA_IMAGES) -> "full"
        context.granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "selected"
        else -> null
    }

    class Item(val id: Long, val takenAt: Long, val width: Int, val height: Int,
               val size: Long, val mime: String, val album: String?,
               val addedAt: Long = 0, val relativePath: String? = null)

    @SuppressLint("Range", "InlinedApi")
    fun recent(limit: Int, type: String = "any", album: String? = null, since: Long? = null, until: Long? = null): List<Item> {
        val args = Bundle().apply {
            // MediaStore ignores QUERY_ARG_SORT_COLUMNS here; the SQL sort string is honoured.
            // Newest-added first: received images (WhatsApp) often have no DATE_TAKEN.
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, NEWEST_FIRST)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_LIMIT, limit.toString())
        }
        val clauses = mutableListOf<String>(); val values = mutableListOf<String>()
        if (type == "screenshot") { clauses += "LOWER(relative_path) LIKE ?"; values += "%screenshots%" }
        if (type == "photo") { clauses += "LOWER(relative_path) LIKE ?"; values += "dcim/camera/%" }
        if (album != null) { clauses += "bucket_display_name = ?"; values += album }
        if (since != null) { clauses += "date_added >= ?"; values += (since / 1000).toString() }
        if (until != null) { clauses += "date_added <= ?"; values += (until / 1000).toString() }
        if (clauses.isNotEmpty()) { args.putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, clauses.joinToString(" AND ")); args.putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, values.toTypedArray()) }
        return query(args, limit)
    }

    enum class Kind { ANY, SCREENSHOT, PHOTO }

    private companion object {
        const val NEWEST_FIRST = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
    }

    /** Classifies by folder: Pixel saves screenshots under .../Screenshots/, camera photos under DCIM/Camera/. */
    fun kindOf(relativePath: String?): String = when {
        relativePath == null -> "other"
        relativePath.contains("Screenshots", ignoreCase = true) -> "screenshot"
        relativePath.startsWith("DCIM/Camera", ignoreCase = true) -> "photo"
        else -> "other"
    }

    /** The most recently added image of [kind], or null. */
    @SuppressLint("InlinedApi")
    fun latest(kind: Kind): Pair<Item, String?>? {
        val args = Bundle().apply {
            when (kind) {
                Kind.SCREENSHOT -> {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("%Screenshots%"))
                }
                Kind.PHOTO -> {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("DCIM/Camera%"))
                }
                Kind.ANY -> {}
            }
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, NEWEST_FIRST)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_LIMIT, "1")
        }
        return query(args, 1).firstOrNull()?.let { it to it.relativePath }
    }

    fun find(id: Long): Item? {
        val args = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media._ID}=?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(id.toString()))
        }
        return query(args).firstOrNull()
    }

    fun read(id: Long): ByteArray? = runCatching {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    /** Android 10 ignores QUERY_ARG_SQL_LIMIT, so reading stops at [limit] here too. */
    @SuppressLint("InlinedApi")
    private fun query(args: Bundle, limit: Int = Int.MAX_VALUE): List<Item> {
        val proj = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH
        )
        val out = mutableListOf<Item>()
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, args, null)?.use { c ->
            while (out.size < limit && c.moveToNext()) {
                val taken = c.getLong(1).takeIf { it > 0 } ?: (c.getLong(2) * 1000)
                var w = c.getInt(3); var h = c.getInt(4)
                if (c.getInt(7) % 180 != 0) { val t = w; w = h; h = t }
                out += Item(c.getLong(0), taken, w, h, c.getLong(5), c.getString(6) ?: "image/jpeg", c.getString(8),
                    c.getLong(2) * 1000, c.getString(9))
            }
        }
        return out
    }
}
