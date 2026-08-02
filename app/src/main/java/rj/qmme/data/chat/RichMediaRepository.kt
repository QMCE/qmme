package rj.qmme.data.chat

import android.util.Log
import com.tencent.qqnt.kernel.nativeinterface.RichMediaElementGetReq
import com.tencent.qqnt.kernel.nativeinterface.RichMediaFilePathInfo
import rj.qmme.kernel.KernelBridge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** PTT path resolve / download request surface for Watch NT rich media. */
object RichMediaRepository {
    private const val TAG = "QMME-RichMedia"
    private const val REQUEST_TIMEOUT_SECONDS = 20L
    private val pendingRequests = ConcurrentHashMap.newKeySet<RichMediaKey>()
    private val pendingTimeouts = ConcurrentHashMap<RichMediaKey, ScheduledFuture<*>>()
    private val requestStates = ConcurrentHashMap<RichMediaKey, RichMediaRequestState>()
    private val timeoutExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "QMME-RichMediaTimeout").apply { isDaemon = true }
    }

    fun requestPttAudio(
        messageId: Long,
        peerUid: String,
        chatType: Int,
        elementId: Long,
    ): Boolean {
        if (messageId <= 0L || elementId <= 0L || peerUid.isBlank()) return false
        val key = RichMediaKey(messageId, elementId)
        if (!beginRequest(key)) return true

        val service = KernelBridge.getMsgService()
        if (service == null) {
            finishRequest(key, RichMediaRequestState.Failed("消息服务不可用"))
            return false
        }

        return runCatching {
            service.getRichMediaElement(
                RichMediaElementGetReq().apply {
                    msgId = messageId
                    this.peerUid = peerUid
                    this.chatType = chatType
                    this.elementId = elementId
                    downloadType = 1
                    downSourceType = 1
                    triggerType = 1
                },
            )
            Log.d(TAG, "richMedia: request ptt audio msg=$messageId, element=$elementId")
            true
        }.getOrElse {
            finishRequest(key, RichMediaRequestState.Failed("语音请求失败"))
            Log.w(TAG, "richMedia: request ptt audio failed", it)
            false
        }
    }

    fun resolvePttPath(media: PttMediaRef): String? {
        LocalMediaResolver.resolveFile(media.filePath)?.let { return it.absolutePath }

        val service = KernelBridge.getMsgService() ?: return null
        val resolved = runCatching {
            service.assembleMobileQQRichMediaFilePath(
                RichMediaFilePathInfo(
                    4,
                    3,
                    media.md5Hex,
                    media.fileName,
                    1,
                    0,
                    media.importRichMediaContext,
                    media.fileUuid,
                    false,
                ),
            )
        }.onFailure {
            Log.w(TAG, "richMedia: resolve PTT path failed msg=${media.messageId}", it)
        }.getOrNull()

        return LocalMediaResolver.resolveFile(resolved)?.absolutePath
    }

    private fun scheduleTimeout(key: RichMediaKey) {
        pendingTimeouts[key] = timeoutExecutor.schedule({
            if (pendingRequests.remove(key)) {
                pendingTimeouts.remove(key)
                requestStates[key] = RichMediaRequestState.Failed("加载超时")
                Log.w(
                    TAG,
                    "richMedia: request timed out msg=${key.messageId}, element=${key.elementId}",
                )
            }
        }, REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun beginRequest(key: RichMediaKey): Boolean {
        if (!pendingRequests.add(key)) return false
        requestStates[key] = RichMediaRequestState.Loading
        scheduleTimeout(key)
        return true
    }

    private fun finishRequest(key: RichMediaKey, state: RichMediaRequestState) {
        pendingRequests.remove(key)
        pendingTimeouts.remove(key)?.cancel(false)
        if (state is RichMediaRequestState.Idle) {
            requestStates.remove(key)
        } else {
            requestStates[key] = state
        }
    }
}
