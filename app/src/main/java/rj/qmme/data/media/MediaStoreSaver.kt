package rj.qmme.data.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves a local or remote image into the user's Pictures/QMME collection. */
class MediaStoreSaver {
    suspend fun saveImage(context: Context, source: String): Result<Unit> = runCatching {
        require(source.isNotBlank()) { "图片地址不可用" }
        val opened = openSource(context.applicationContext, source)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                saveLegacy(context, opened)
            } else {
                saveModern(context, opened)
            }
        } finally {
            opened.disconnect()
        }
    }

    private fun saveModern(context: Context, opened: OpenedSource) {
        val extension = extensionForMime(opened.mimeType)
        val displayName = displayName(extension)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, opened.mimeType)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/QMME",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建媒体文件")
        try {
            opened.input.use { input ->
                resolver.openOutputStream(uri)?.use(input::copyTo)
                    ?: error("无法写入媒体文件")
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, opened: OpenedSource) {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "QMME",
        )
        if (!directory.exists() && !directory.mkdirs()) error("无法创建图片目录")
        val output = File(directory, displayName(extensionForMime(opened.mimeType)))
        opened.input.use { input -> output.outputStream().use(input::copyTo) }
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(output.absolutePath),
            arrayOf(opened.mimeType),
            null,
        )
    }

    private fun openSource(context: Context, source: String): OpenedSource {
        val uri = runCatching { Uri.parse(source) }.getOrNull()
        return when (uri?.scheme?.lowercase(Locale.ROOT)) {
            "http", "https" -> {
                val connection = (URL(source).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                }
                connection.connect()
                check(connection.responseCode in 200..299) {
                    "下载失败：HTTP ${connection.responseCode}"
                }
                check(connection.contentLengthLong <= MAX_BYTES || connection.contentLengthLong < 0L) {
                    "图片文件过大"
                }
                OpenedSource(
                    input = connection.inputStream,
                    mimeType = connection.contentType?.substringBefore(';')
                        ?.takeIf { it.startsWith("image/") }
                        ?: guessMimeType(source),
                    disconnect = connection::disconnect,
                )
            }

            "content" -> OpenedSource(
                input = context.contentResolver.openInputStream(uri) ?: error("无法读取图片"),
                mimeType = context.contentResolver.getType(uri)
                    ?.takeIf { it.startsWith("image/") }
                    ?: guessMimeType(source),
            )

            else -> {
                val file = File(source.removePrefix("file://"))
                check(file.isFile && file.length() > 0L) { "本地图片不可用" }
                check(file.length() <= MAX_BYTES) { "图片文件过大" }
                OpenedSource(file.inputStream(), guessMimeType(file.name))
            }
        }
    }

    private fun displayName(extension: String): String =
        "QMME_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())}.$extension"

    private fun extensionForMime(mimeType: String): String = when (mimeType) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        else -> "jpg"
    }

    private fun guessMimeType(name: String): String = when (
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    ) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }

    private class OpenedSource(
        val input: InputStream,
        val mimeType: String,
        private val disconnect: () -> Unit = {},
    ) {
        fun disconnect() = disconnect.invoke()
    }

    private companion object {
        const val MAX_BYTES = 16L * 1024L * 1024L
    }
}
