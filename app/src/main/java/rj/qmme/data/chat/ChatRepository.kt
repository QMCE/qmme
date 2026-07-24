package rj.qmme.data.chat

import android.util.Log
import com.tencent.qqnt.kernel.nativeinterface.GetMsgsAndStatusRecord
import com.tencent.qqnt.kernel.nativeinterface.GetMsgsStatusEnum
import com.tencent.qqnt.kernel.nativeinterface.IGetAioFirstViewLatestMsgCallback
import com.tencent.qqnt.kernel.nativeinterface.IGetMsgWithStatusCallback
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgListener
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.MsgAttributeInfo
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.kernel.nativeinterface.RichMediaFilePathInfo
import com.tencent.qqnt.kernel.nativeinterface.TextElement
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact
import mqq.app.AppRuntime
import rj.qmme.kernel.KernelBridge
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong

/**
 * QMME adaptation of QMCE's ChatRepository.
 *
 * The official Watch runtime exposes message operations through the lower-level
 * IKernelMsgService and uses kernelpublic.Contact, unlike QMCE's qq-sdk.jar.
 * Keeping that ABI translation here prevents it from leaking into UI code.
 */
class ChatRepository {
    data class HistoryRequest(
        val contact: Contact,
        val anchorMessageId: Long,
        val anchorMessageTime: Long,
        val count: Int,
    )

    sealed interface Connection {
        data object Ready : Connection
        data object KernelUnavailable : Connection
        data class MessageServiceUnavailable(val timedOut: Boolean) : Connection
    }

    interface Listener {
        fun onReceived(messages: ArrayList<MsgRecord>)
        fun onAddedSendMessage(message: MsgRecord)
        fun onMessageUpdated(messages: ArrayList<MsgRecord>) = Unit
        fun onMessageDeleted(contact: Contact, messageIds: ArrayList<Long>) = Unit
    }

    private var listenerId = 0L
    private var listenerProxy: IKernelMsgListener? = null
    private var listenerService: IKernelMsgService? = null

    fun connect(runtime: AppRuntime?): Connection {
        val ready = KernelBridge.awaitCoreServices(runtimeOverride = runtime)
        return if (KernelBridge.getKernelMsgService() != null) {
            Connection.Ready
        } else if (!ready) {
            Connection.MessageServiceUnavailable(timedOut = true)
        } else {
            Connection.KernelUnavailable
        }
    }

    fun isConnected(): Boolean = KernelBridge.getKernelMsgService() != null

    fun loadLatest(
        contact: Contact,
        count: Int,
        callback: (
            errorCode: Int,
            errorMessage: String?,
            messages: ArrayList<MsgRecord>?,
            needContinue: Boolean,
        ) -> Unit,
    ): Boolean {
        val service = KernelBridge.getKernelMsgService() ?: return false
        return runCatching {
            service.getAioFirstViewLatestMsgs(
                contact,
                count,
                object : IGetAioFirstViewLatestMsgCallback {
                    override fun onResult(
                        errorCode: Int,
                        errorMessage: String?,
                        messages: ArrayList<MsgRecord>?,
                        needContinue: Boolean,
                    ) = callback(errorCode, errorMessage, messages, needContinue)
                },
            )
            true
        }.onFailure { Log.w(TAG, "load latest messages failed", it) }
            .getOrDefault(false)
    }

    fun loadOlder(
        request: HistoryRequest,
        callback: (
            errorCode: Int,
            errorMessage: String?,
            status: GetMsgsStatusEnum?,
            messages: ArrayList<MsgRecord>?,
        ) -> Unit,
    ): Boolean {
        val service = KernelBridge.getKernelMsgService() ?: return false
        return runCatching {
            service.getMsgsWithStatus(
                GetMsgsAndStatusRecord().apply {
                    peer = request.contact
                    msgId = request.anchorMessageId
                    msgTime = request.anchorMessageTime
                    cnt = request.count
                    queryOrder = true
                    isIncludeSelf = false
                    appid = 0L
                },
                object : IGetMsgWithStatusCallback {
                    override fun onResult(
                        errorCode: Int,
                        errorMessage: String?,
                        status: GetMsgsStatusEnum?,
                        messages: ArrayList<MsgRecord>?,
                    ) = callback(errorCode, errorMessage, status, messages)
                },
            )
            true
        }.onFailure { Log.w(TAG, "load older messages failed", it) }
            .getOrDefault(false)
    }

    fun startListening(listener: Listener): Boolean {
        stopListening()
        val service = KernelBridge.getKernelMsgService() ?: return false
        val proxy = Proxy.newProxyInstance(
            IKernelMsgListener::class.java.classLoader,
            arrayOf(IKernelMsgListener::class.java),
        ) { proxyInstance, method, args ->
            when (method.name) {
                "onRecvMsg" -> {
                    @Suppress("UNCHECKED_CAST")
                    (args?.getOrNull(0) as? ArrayList<MsgRecord>)?.let(listener::onReceived)
                    null
                }

                "onAddSendMsg" -> {
                    (args?.getOrNull(0) as? MsgRecord)?.let(listener::onAddedSendMessage)
                    null
                }

                "onMsgInfoListUpdate" -> {
                    @Suppress("UNCHECKED_CAST")
                    (args?.getOrNull(0) as? ArrayList<MsgRecord>)?.let(listener::onMessageUpdated)
                    null
                }

                "onMsgDelete" -> {
                    val contact = args?.getOrNull(0) as? Contact
                    @Suppress("UNCHECKED_CAST")
                    val ids = args?.getOrNull(1) as? ArrayList<Long>
                    if (contact != null && ids != null) listener.onMessageDeleted(contact, ids)
                    null
                }

                "hashCode" -> System.identityHashCode(proxyInstance)
                "equals" -> proxyInstance === args?.getOrNull(0)
                "toString" -> "QMME-ChatRepositoryListener"
                else -> defaultValue(method.returnType)
            }
        } as IKernelMsgListener

        return runCatching {
            val id = service.addKernelMsgListener(proxy)
            if (id <= 0L) error("invalid message listener id=$id")
            listenerId = id
            listenerProxy = proxy
            listenerService = service
            true
        }.onFailure { Log.w(TAG, "register message listener failed", it) }
            .getOrDefault(false)
    }

    fun sendText(
        contact: Contact,
        text: String,
        callback: (errorCode: Int, errorMessage: String?) -> Unit,
    ): Boolean {
        val normalized = text.trim()
        if (normalized.isBlank()) return false
        val element = MsgElement().apply {
            elementType = 1
            elementId = 0L
            textElement = TextElement().apply {
                content = normalized
                atType = 0
                atUid = 0L
                atNtUid = ""
            }
        }
        return sendMessage(contact, arrayListOf(element), callback)
    }

    fun sendMessage(
        contact: Contact,
        elements: ArrayList<MsgElement>,
        callback: (errorCode: Int, errorMessage: String?) -> Unit,
    ): Boolean {
        val service = KernelBridge.getKernelMsgService() ?: return false
        return runCatching {
            service.sendMsg(
                MESSAGE_UNIQUE_ID.incrementAndGet(),
                contact,
                elements,
                hashMapOf<Int, MsgAttributeInfo>(),
                object : IOperateCallback {
                    override fun onResult(errorCode: Int, errorMessage: String?) {
                        callback(errorCode, errorMessage)
                    }
                },
            )
            true
        }.onFailure { Log.w(TAG, "send message failed", it) }
            .getOrDefault(false)
    }

    fun recallMessage(
        contact: Contact,
        messageId: Long,
        callback: (errorCode: Int, errorMessage: String?) -> Unit,
    ): Boolean {
        if (messageId <= 0L) return false
        val service = KernelBridge.getKernelMsgService() ?: return false
        return runCatching {
            // QMME's Watch ABI accepts a batch even for a single recall.  QMCE's
            // higher-level wrapper exposes a scalar overload, so this is an ABI
            // adaptation rather than a direct source copy.
            service.recallMsg(
                contact,
                arrayListOf(messageId),
                object : IOperateCallback {
                    override fun onResult(errorCode: Int, errorMessage: String?) {
                        callback(errorCode, errorMessage)
                    }
                },
            )
            true
        }.onFailure { Log.w(TAG, "recall message failed id=$messageId", it) }
            .getOrDefault(false)
    }

    fun deleteMessages(
        contact: Contact,
        messageIds: Collection<Long>,
        callback: (errorCode: Int, errorMessage: String?) -> Unit,
    ): Boolean {
        val ids = messageIds.filter { it > 0L }.distinct()
        if (ids.isEmpty()) return false
        val service = KernelBridge.getKernelMsgService() ?: return false
        return runCatching {
            service.deleteMsg(
                contact,
                ArrayList(ids),
                object : IOperateCallback {
                    override fun onResult(errorCode: Int, errorMessage: String?) {
                        callback(errorCode, errorMessage)
                    }
                },
            )
            true
        }.onFailure { Log.w(TAG, "delete messages failed ids=$ids", it) }
            .getOrDefault(false)
    }

    fun resendMessage(
        contact: Contact,
        messageId: Long,
        callback: (errorCode: Int, errorMessage: String?) -> Unit,
    ): Boolean {
        if (messageId <= 0L) return false
        val service = KernelBridge.getKernelMsgService() ?: return false
        return runCatching {
            service.resendMsg(
                contact,
                messageId,
                object : IOperateCallback {
                    override fun onResult(errorCode: Int, errorMessage: String?) {
                        callback(errorCode, errorMessage)
                    }
                },
            )
            true
        }.onFailure { Log.w(TAG, "resend message failed id=$messageId", it) }
            .getOrDefault(false)
    }

    fun getMobileQQSendPath(info: RichMediaFilePathInfo): String? = runCatching {
        KernelBridge.getKernelMsgService()?.getRichMediaFilePathForMobileQQSend(info)
    }.onFailure { Log.w(TAG, "resolve rich media send path failed", it) }
        .getOrNull()
        ?.takeIf(String::isNotBlank)

    fun markMessagesRead(
        contact: Contact,
        callback: (errorCode: Int, errorMessage: String?) -> Unit = { _, _ -> },
    ): Boolean {
        val service = KernelBridge.getKernelMsgService() ?: return false
        return runCatching {
            service.setMsgRead(
                contact,
                object : IOperateCallback {
                    override fun onResult(errorCode: Int, errorMessage: String?) {
                        callback(errorCode, errorMessage)
                    }
                },
            )
            true
        }.onFailure { Log.w(TAG, "mark messages read failed", it) }
            .getOrDefault(false)
    }

    fun stopListening() {
        val id = listenerId
        val service = listenerService
        listenerId = 0L
        listenerProxy = null
        listenerService = null
        if (id <= 0L) return
        runCatching { service?.removeKernelMsgListener(id) }
            .onFailure { Log.w(TAG, "remove message listener failed id=$id", it) }
    }

    fun close() = stopListening()

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private companion object {
        const val TAG = "QMME-Chat"
        val MESSAGE_UNIQUE_ID = AtomicLong(System.currentTimeMillis())
    }
}
