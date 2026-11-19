package dev.offreel.ui.common

import java.io.File
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.offreel.capture.CaptureService
import dev.offreel.capture.InstagramDriver
import dev.offreel.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The offreel mark: an open ring with a play triangle. */
@Composable
fun OffreelMark(size: Dp, ringColor: Color, modifier: Modifier = Modifier, playColor: Color = Brand.Ember) {
    Canvas(modifier.size(size)) {
        val c = this.size.minDimension / 2f
        val r = c * 0.78f
        val stroke = c * 0.17f
        drawArc(
            color = ringColor, startAngle = -100f, sweepAngle = 300f, useCenter = false,
            topLeft = Offset(c - r, c - r), size = Size(r * 2, r * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        val t = r * 0.62f
        val p = Path().apply {
            moveTo(c - t * 0.55f, c - t)
            lineTo(c + t * 0.95f, c)
            lineTo(c - t * 0.55f, c + t)
            close()
        }
        drawPath(p, playColor)
    }
}

private object ThumbCache : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

/** A local thumbnail file, loaded off the main thread and cached. */
@Composable
fun Thumb(path: String?, modifier: Modifier = Modifier, placeholder: Color, contentScale: ContentScale = ContentScale.Crop) {
    val image by produceState<ImageBitmap?>(initialValue = path?.let { ThumbCache.get(it)?.asImageBitmap() }, path) {
        if (path == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.also { ThumbCache.put(path, it) }?.asImageBitmap()
        }
    }
    val img = image
    if (img != null) {
        Image(img, contentDescription = null, contentScale = contentScale, modifier = modifier)
    } else {
        Box(modifier.background(placeholder))
    }
}

/** Is offreel's accessibility service switched on in system settings? */
fun isCaptureServiceEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    val me = ComponentName(context, CaptureService::class.java)
    val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
    return splitter.any { ComponentName.unflattenFromString(it) == me }
}

fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

/** App details page, where "Allow restricted settings" lives for apps installed outside the Play Store. */
fun openAppDetails(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

fun openInInstagram(context: Context, url: String) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(Intent(view).setPackage(InstagramDriver.IG))
    } catch (_: ActivityNotFoundException) {
        try { context.startActivity(view) } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }
}

private val HANDLE = Regex("^[A-Za-z0-9._]{1,30}$")

/** True when [author] looks like an Instagram @handle (not a display name with spaces). */
fun isInstagramHandle(author: String?) = author != null && HANDLE.matches(author)

/** Opens the creator's profile in Instagram (or the browser if Instagram isn't installed). */
fun openInstagramProfile(context: Context, handle: String) =
    openInInstagram(context, "https://www.instagram.com/$handle/")

/**
 * Shares saved videos (the files themselves) to any app: WhatsApp, Instagram, Telegram…
 * Other apps get temporary read access to just these files through offreel's FileProvider.
 */
fun shareVideos(context: Context, reels: List<dev.offreel.data.ReelEntity>) {
    val files = reels.mapNotNull { r -> r.videoPath?.let { File(it) }?.takeIf { it.exists() } }
    if (files.isEmpty()) {
        Toast.makeText(context, "Not downloaded yet", Toast.LENGTH_SHORT).show()
        return
    }
    val authority = "${context.packageName}.files"
    val uris = files.map { androidx.core.content.FileProvider.getUriForFile(context, authority, it) }
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }.apply {
        type = "video/mp4"
        clipData = android.content.ClipData.newRawUri(null, uris.first()).also { c ->
            uris.drop(1).forEach { c.addItem(android.content.ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, if (uris.size == 1) "Share video" else "Share ${uris.size} videos")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
