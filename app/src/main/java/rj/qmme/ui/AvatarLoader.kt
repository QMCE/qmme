package rj.qmme.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import com.google.android.material.appbar.MaterialToolbar
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.tencent.mobileqq.qroute.QRoute
import com.tencent.qqnt.avatar.IAvatarLoaderApi
import com.tencent.qqnt.avatar.IAvatarRequestLoad
import com.tencent.qqnt.avatar.WatchAvatarView
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rj.qmme.viewmodel.ContactsViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.WeakHashMap
import kotlin.coroutines.coroutineContext

/**
 * Small native ImageView loader used by the RecyclerView rows.
 *
 * The QQ avatar module is still used as a best-effort prefetch path (see
 * [OfficialAvatarLoader]), but the visible fallback deliberately does not
 * depend on URLDrawable, Coil, Compose, or any QQ UI implementation.  This is
 * important for the watch APK because URLDrawable is not present in the
 * curated qq-core runtime.
 */
internal object AvatarLoader {
    private const val TAG = "QMME-Avatar"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024
    private const val MAX_DECODE_DIMENSION = 512

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memoryCache = object : LruCache<String, Bitmap>(memoryCacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val jobs = WeakHashMap<ImageView, Job>()
    private val requestIds = WeakHashMap<ImageView, Long>()
    private val navigationJobs = WeakHashMap<MaterialToolbar, Job>()
    private val navigationRequestIds = WeakHashMap<MaterialToolbar, Long>()
    private var nextRequestId = 0L

    /** A relative 50% corner size is a real circle for our square avatar views. */
    private val circularAvatarShape = ShapeAppearanceModel.builder()
        .setAllCornerSizes(ShapeAppearanceModel.PILL)
        .build()

    /** Apply the same crop to every visible avatar entry point. */
    fun makeCircular(imageView: ImageView) {
        (imageView as? ShapeableImageView)?.shapeAppearanceModel = circularAvatarShape
    }

    /**
     * Reset the recycled row immediately, then load local avatar and remote
     * fallback URLs off the main thread.  Every completion checks a per-view
     * request id so a fast scroll cannot attach another contact's avatar.
     */
    fun bind(
        imageView: ImageView,
        localPath: String?,
        urls: List<String>,
        fallback: Drawable?,
    ) {
        makeCircular(imageView)
        val appContext = imageView.context.applicationContext
        val requestId: Long
        synchronized(this) {
            jobs.remove(imageView)?.cancel()
            requestId = ++nextRequestId
            requestIds[imageView] = requestId
        }

        imageView.setImageDrawable(fallback)
        val normalizedUrls = urls
            .asSequence()
            .map(String::trim)
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .toList()
        if (localPath.isNullOrBlank() && normalizedUrls.isEmpty()) return

        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val bitmap = try {
                load(appContext, localPath, normalizedUrls)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.d(TAG, "avatar load failed", error)
                null
            }
            withContext(Dispatchers.Main.immediate) {
                val current = synchronized(this@AvatarLoader) {
                    requestIds[imageView] == requestId
                }
                if (!current) return@withContext
                if (bitmap != null && !bitmap.isRecycled) {
                    imageView.setImageBitmap(bitmap)
                } else {
                    imageView.setImageDrawable(fallback)
                }
                synchronized(this@AvatarLoader) {
                    if (requestIds[imageView] == requestId) {
                        jobs.remove(imageView)
                    }
                }
            }
        }
        synchronized(this) {
            // A bind can only be called on the UI thread, but assigning the job
            // before starting it also closes the tiny cancellation race.
            jobs[imageView] = job
        }
        job.start()
    }

    fun unbind(imageView: ImageView) {
        synchronized(this) {
            jobs.remove(imageView)?.cancel()
            requestIds.remove(imageView)
        }
    }

    /**
     * Load the account avatar into MaterialToolbar's native leading navigation
     * slot.  A toolbar custom child is not suitable here: Toolbar places it
     * after title/subtitle regardless of its child gravity.
     */
    fun bindNavigationIcon(
        toolbar: MaterialToolbar,
        localPath: String?,
        urls: List<String>,
        fallback: Drawable?,
    ) {
        val appContext = toolbar.context.applicationContext
        val requestId: Long
        synchronized(this) {
            navigationJobs.remove(toolbar)?.cancel()
            requestId = ++nextRequestId
            navigationRequestIds[toolbar] = requestId
        }

        toolbar.navigationIcon = fallback
        toolbar.navigationContentDescription = "我的头像"
        val normalizedUrls = urls
            .asSequence()
            .map(String::trim)
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .toList()
        if (localPath.isNullOrBlank() && normalizedUrls.isEmpty()) return

        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val bitmap = try {
                load(appContext, localPath, normalizedUrls)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.d(TAG, "toolbar avatar load failed", error)
                null
            }
            withContext(Dispatchers.Main.immediate) {
                val current = synchronized(this@AvatarLoader) {
                    navigationRequestIds[toolbar] == requestId
                }
                if (!current) return@withContext
                toolbar.navigationIcon = bitmap
                    ?.takeUnless(Bitmap::isRecycled)
                    ?.let { circularToolbarAvatar(toolbar, it) }
                    ?: fallback
                synchronized(this@AvatarLoader) {
                    if (navigationRequestIds[toolbar] == requestId) {
                        navigationJobs.remove(toolbar)
                    }
                }
            }
        }
        synchronized(this) {
            navigationJobs[toolbar] = job
        }
        job.start()
    }

    fun unbindNavigationIcon(toolbar: MaterialToolbar) {
        synchronized(this) {
            navigationJobs.remove(toolbar)?.cancel()
            navigationRequestIds.remove(toolbar)
        }
    }

    private fun circularToolbarAvatar(toolbar: MaterialToolbar, bitmap: Bitmap): Drawable =
        RoundedBitmapDrawableFactory.create(toolbar.resources, bitmap).apply {
            isCircular = true
        }

    private suspend fun load(
        context: Context,
        localPath: String?,
        urls: List<String>,
    ): Bitmap? {
        if (!localPath.isNullOrBlank()) {
            val localKey = "local:${localPath.trim()}"
            memoryCache.get(localKey)?.let { return it }
            decodeLocal(context, localPath)?.let { bitmap ->
                memoryCache.put(localKey, bitmap)
                return bitmap
            }
        }

        for (url in urls) {
            coroutineContext.ensureActive()
            val key = "url:$url"
            memoryCache.get(key)?.let { return it }

            val diskFile = cacheFile(context, key)
            decodeDiskFile(diskFile)?.let { bitmap ->
                memoryCache.put(key, bitmap)
                return bitmap
            }
            if (diskFile.exists() && diskFile.length() > MAX_DOWNLOAD_BYTES) {
                diskFile.delete()
            }

            val bytes = download(url) ?: continue
            coroutineContext.ensureActive()
            val bitmap = decodeBytes(bytes)
            if (bitmap == null) {
                Log.d(TAG, "not a bitmap: $url")
                continue
            }
            writeCacheAtomically(diskFile, bytes)
            memoryCache.put(key, bitmap)
            return bitmap
        }
        return null
    }

    private fun decodeLocal(context: Context, path: String): Bitmap? {
        val raw = path.trim()
        return try {
            val uri = Uri.parse(raw)
            when (uri.scheme?.lowercase()) {
                "content" -> context.contentResolver.openInputStream(uri)?.use(::decodeStream)
                "file" -> uri.path?.let { decodeFile(File(it)) }
                else -> decodeFile(File(Uri.decode(raw.removePrefix("file://"))))
            }
        } catch (error: Throwable) {
            Log.d(TAG, "local avatar decode failed: $raw", error)
            null
        }
    }

    private fun decodeFile(file: File): Bitmap? {
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_DOWNLOAD_BYTES) return null
        return FileInputStream(file).use { input -> decodeStream(input) }
    }

    private fun decodeDiskFile(file: File): Bitmap? {
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_DOWNLOAD_BYTES) return null
        return try {
            FileInputStream(file).use { input -> decodeStream(input) }
        } catch (_: IOException) {
            file.delete()
            null
        }
    }

    private fun decodeStream(input: java.io.InputStream): Bitmap? {
        // Decode bounds first so an accidentally large local/network image does
        // not allocate a full-size bitmap for a 44/46dp watch avatar.
        val bytes = input.readCappedBytes() ?: return null
        return decodeBytes(bytes)
    }

    private fun decodeBytes(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var maxDimension = maxOf(width, height)
        while (maxDimension / sample > MAX_DECODE_DIMENSION) sample *= 2
        return sample
    }

    private fun download(url: String): ByteArray? {
        val connection = try {
            URL(url).openConnection() as? HttpURLConnection
        } catch (_: Throwable) {
            null
        } ?: return null

        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.useCaches = true
            connection.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/jpeg,*/*")
            connection.setRequestProperty("User-Agent", "QMME/1.0 Android")
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.d(TAG, "avatar http=$code url=$url")
                return null
            }
            if (connection.contentLengthLong > MAX_DOWNLOAD_BYTES) return null
            connection.inputStream.use { it.readCappedBytes() }
        } catch (error: IOException) {
            Log.d(TAG, "avatar http failed url=$url", error)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readCappedBytes(): ByteArray? {
        val output = ByteArrayOutputStream(32 * 1024)
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > MAX_DOWNLOAD_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun cacheFile(context: Context, key: String): File {
        val directory = File(context.cacheDir, "qmme-avatars")
        if (!directory.exists()) directory.mkdirs()
        return File(directory, sha256(key) + ".img")
    }

    private fun writeCacheAtomically(file: File, bytes: ByteArray) {
        try {
            val temporary = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(temporary).use { it.write(bytes) }
            if (!temporary.renameTo(file)) temporary.delete()
        } catch (error: IOException) {
            Log.d(TAG, "avatar cache write failed", error)
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun memoryCacheSize(): Int {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        return (maxMemoryKb / 8).coerceAtLeast(2 * 1024)
    }
}

/** Best-effort use of QQ's official avatar service, matching qmce-lite-x. */
internal object OfficialAvatarLoader {
    private const val TAG = "QMME-Avatar"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = WeakHashMap<WatchAvatarView, IAvatarRequestLoad>()

    fun bind(view: WatchAvatarView, uin: Long, uid: String = "") {
        val old = synchronized(this) { requests.remove(view) }
        runCatching { old?.unBind() }
        if (uin <= 0L) {
            view.alpha = 0f
            return
        }

        runCatching {
            val request = QRoute.api(IAvatarLoaderApi::class.java)
                .build(view.context)
                .target(view)
            synchronized(this) { requests[view] = request }
            request.loadAvatarByUid(uid, uin, scope)
            view.alpha = 0f
        }.onFailure { error ->
            // qq-core can be used without the avatar implementation being
            // registered in QRoute.  The visible native fallback remains active.
            Log.d(TAG, "official avatar unavailable uin=$uin", error)
        }
    }

    fun unbind(view: WatchAvatarView) {
        val request = synchronized(this) { requests.remove(view) }
        runCatching { request?.unBind() }
    }
}

internal object AvatarSources {
    fun forSelf(uin: String): List<String> =
        uin.toLongOrNull()?.takeIf { it > 0L }?.let(::qlogoUrls).orEmpty()

    fun forRecent(contact: RecentContactInfo): List<String> {
        val result = ArrayList<String>(4)
        contact.avatarUrl
            ?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let(result::add)

        val id = contact.id.orEmpty().trim()
        if (contact.chatType == 2) {
            val groupId = id.takeIf { it.isNotBlank() }
                ?: contact.peerUin.takeIf { it > 0L }?.toString()
            if (!groupId.isNullOrBlank()) {
                result += "https://p.qlogo.cn/gh/$groupId/$groupId/100"
            }
        } else {
            val uin = contact.peerUin.takeIf { it > 0L }
                ?: id.toLongOrNull()?.takeIf { it > 0L }
            if (uin != null) result += qlogoUrls(uin)
        }
        return result.distinct()
    }

    fun forBuddy(buddy: ContactsViewModel.UiBuddy): List<String> {
        val result = buddy.avatarUrls.filter {
            it.startsWith("http://") || it.startsWith("https://")
        }.toMutableList()
        if (buddy.uin > 0L) result += qlogoUrls(buddy.uin)
        return result.distinct()
    }

    private fun qlogoUrls(uin: Long): List<String> = listOf(
        "https://q1.qlogo.cn/g?b=qq&nk=$uin&s=100",
        "https://q2.qlogo.cn/headimg_dl?dst_uin=$uin&spec=100",
        "https://qlogo2.store.qq.com/qzone/$uin/$uin/100",
    )
}
