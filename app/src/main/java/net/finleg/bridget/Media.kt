package net.finleg.bridget

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

/** A structured API error, mapped 1:1 to the spec's error vocabulary. */
class ApiError(val http: Int, val code: String, message: String, val extra: Map<String, Any?> = emptyMap()) :
    Exception(message)

class Encoded(val bytes: ByteArray, val width: Int, val height: Int, val mime: String)

fun Context.granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

fun isoAt(epochMs: Long): String =
    OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
        .truncatedTo(ChronoUnit.SECONDS).toString()

object ImageUtil {
    /**
     * Applies EXIF rotation and downscales to [maxWidth] if needed. Returns the
     * original bytes untouched when nothing needs to change.
     */
    fun prepare(original: ByteArray, mime: String, maxWidth: Int?): Encoded {
        val exif = runCatching { ExifInterface(original.inputStream()) }.getOrNull()
        val rotation = exif?.rotationDegrees ?: 0
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        val (w, h) = if (rotation % 180 != 0) rawH to rawW else rawW to rawH
        if (rawW <= 0) throw ApiError(500, "capture_failed", "Could not decode image")
        if ((maxWidth == null || w <= maxWidth) && rotation == 0) return Encoded(original, w, h, mime)

        val target = if (maxWidth != null && w > maxWidth) maxWidth else w
        var sample = 1
        while (w / (sample * 2) >= target) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(original, 0, original.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw ApiError(500, "capture_failed", "Could not decode image")
        val m = Matrix()
        if (rotation != 0) m.postRotate(rotation.toFloat())
        val rotatedW = if (rotation % 180 != 0) bmp.height else bmp.width
        val scale = target.toFloat() / rotatedW
        if (scale < 1f) m.postScale(scale, scale)
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        val res = Encoded(bos.toByteArray(), out.width, out.height, "image/jpeg")
        if (out !== bmp) out.recycle()
        bmp.recycle()
        return res
    }
}

class CameraCapture(private val context: Context, private val owner: LifecycleOwner) {

    suspend fun snapshot(rear: Boolean, flash: Boolean, maxWidth: Int?): Encoded {
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
            try {
                withContext(Dispatchers.Main) { provider.bindToLifecycle(owner, selector, capture) }
            } catch (e: Exception) {
                throw ApiError(409, "sensor_busy", "Camera unavailable: ${e.message}")
            }
            try {
                val err = withTimeoutOrNull(20_000) { take(capture, file) }
                    ?: throw ApiError(504, "capture_timeout", "Camera did not produce a photo in time")
                if (err != null) {
                    val code = if (err.imageCaptureError == ImageCapture.ERROR_CAMERA_CLOSED) "sensor_busy" else "sensor_busy"
                    throw ApiError(409, code, "Capture failed: ${err.message}")
                }
            } finally {
                withContext(Dispatchers.Main) { provider.unbind(capture) }
            }
            return ImageUtil.prepare(file.readBytes(), "image/jpeg", maxWidth)
        } finally {
            file.delete()
        }
    }

    /** Returns null on success, the exception on failure. */
    private suspend fun take(capture: ImageCapture, file: File): ImageCaptureException? =
        suspendCancellableCoroutine { cont ->
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(r: ImageCapture.OutputFileResults) { if (cont.isActive) cont.resume(null) }
                    override fun onError(e: ImageCaptureException) { if (cont.isActive) cont.resume(e) }
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
        val rec = MediaRecorder(context)
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
               val size: Long, val mime: String, val album: String?)

    @SuppressLint("Range")
    fun recent(limit: Int): List<Item> {
        val args = Bundle().apply {
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        return query(args)
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

    private fun query(args: Bundle): List<Item> {
        val proj = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        val out = mutableListOf<Item>()
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, args, null)?.use { c ->
            while (c.moveToNext()) {
                val taken = c.getLong(1).takeIf { it > 0 } ?: (c.getLong(2) * 1000)
                var w = c.getInt(3); var h = c.getInt(4)
                if (c.getInt(7) % 180 != 0) { val t = w; w = h; h = t }
                out += Item(c.getLong(0), taken, w, h, c.getLong(5), c.getString(6) ?: "image/jpeg", c.getString(8))
            }
        }
        return out
    }
}
