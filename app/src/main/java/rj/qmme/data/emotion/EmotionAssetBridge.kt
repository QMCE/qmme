package rj.qmme.data.emotion

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Lightweight reimplementation of the QMCE emotion asset bridge.
 *
 * NT's resource manager reads system-emotion files from `filesDir/qq_emoticon_res` rather than APK
 * assets, so this materializes any bundled config/zip that ships under `assets/` before the manager
 * starts. Unlike the original, it is fully self-contained and **no-ops safely when no emotion assets
 * are bundled** (the default for this project), so it never blocks startup.
 */
object EmotionAssetBridge {
    private const val TAG = "QMME-EmotionAssets"
    private const val RESOURCE_DIR = "qq_emoticon_res"
    private const val CONFIG_ASSET = "face_config.json"
    private val ZIP_ASSETS = listOf("bigface.zip", "emoji_res.zip")

    @Volatile
    private var resourceRoot: File? = null

    @Volatile
    private var applicationContext: Context? = null

    fun ensure(context: Context) {
        synchronized(this) {
            // During Application.attachBaseContext(), a ContextWrapper may not yet
            // expose an application context. Fall back to the supplied base context.
            val appContext = context.applicationContext ?: context
            applicationContext = appContext
            val resourceDir = File(appContext.filesDir, RESOURCE_DIR)
            resourceRoot = resourceDir
            runCatching { ensureLocked(appContext, resourceDir) }
                .onFailure { Log.e(TAG, "emotion resource initialization failed", it) }
        }
    }

    private fun ensureLocked(context: Context, resourceDir: File) {
        val bundled = context.assets.list("")?.toSet().orEmpty()
        if (CONFIG_ASSET !in bundled) {
            Log.i(TAG, "no bundled emotion assets; skipping (fallback rendering stays enabled)")
            return
        }
        if (!resourceDir.exists() && !resourceDir.mkdirs()) {
            error("cannot create ${resourceDir.absolutePath}")
        }
        copyAsset(context, resourceDir, CONFIG_ASSET)
        ZIP_ASSETS.filter { it in bundled }.forEach { extractZipAsset(context, resourceDir, it) }
        Log.i(TAG, "emotion resources ready dir=${resourceDir.absolutePath}")
    }

    fun resourceFile(relativePath: String): File? {
        val root = resourceRoot
            ?: applicationContext?.let { File(it.filesDir, RESOURCE_DIR) }
            ?: return null
        val output = File(root, relativePath)
        return runCatching {
            require(output.canonicalPath.startsWith(root.canonicalPath + File.separator))
            output
        }.getOrNull()
    }

    private fun copyAsset(context: Context, resourceDir: File, assetName: String) {
        val target = File(resourceDir, assetName)
        context.assets.open(assetName).use { input ->
            target.outputStream().buffered().use { input.copyTo(it) }
        }
    }

    private fun extractZipAsset(context: Context, resourceDir: File, assetName: String) {
        context.assets.open(assetName).buffered().use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val output = safeEntry(resourceDir, entry.name)
                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        output.outputStream().buffered().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
        }
        Log.i(TAG, "extracted bundled resources from $assetName")
    }

    private fun safeEntry(root: File, entryName: String): File {
        require(entryName.isNotBlank()) { "empty zip entry" }
        val output = File(root, entryName)
        val rootPath = root.canonicalPath + File.separator
        require(output.canonicalPath.startsWith(rootPath)) { "invalid zip entry: $entryName" }
        return output
    }
}
