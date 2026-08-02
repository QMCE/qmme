package rj.qmme.viewmodel

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tencent.mobileqq.qroute.QRoute
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.kernel.nativeinterface.PicElement
import com.tencent.qqnt.kernel.nativeinterface.PttElement
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import com.tencent.qqnt.kernel.nativeinterface.RichMediaFilePathInfo
import com.tencent.qqnt.kernel.nativeinterface.TextElement
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact
import com.tencent.qqnt.msg.api.IMsgUtilApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rj.qmme.data.chat.ChatRepository
import rj.qmme.data.chat.OfficialPttPlayer
import rj.qmme.data.chat.PttMediaRef
import rj.qmme.data.chat.PttPlaybackState
import rj.qmme.data.chat.RichMediaRepository
import rj.qmme.runtime.RuntimeCoordinator
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.round

/** Chat detail: history, live updates, text/image/voice send, reply, forward, multi-select. */
class ChatDetailViewModel : ViewModel() {
    data class UiImage(
        val localPaths: List<String>,
        val remoteUrls: List<String>,
        val width: Int,
        val height: Int,
    )

    data class UiVoice(
        val media: PttMediaRef,
        val durationSeconds: Int,
    )

    data class ReplyPreview(
        val senderName: String,
        val summary: String,
    )

    data class ReplyTarget(
        val messageId: Long,
        val senderUid: String,
        val senderName: String,
        val summary: String,
    )

    data class UiMessage(
        val stableId: Long,
        val messageId: Long,
        val sequence: Long,
        val timestampSeconds: Long,
        val senderName: String,
        val senderUid: String,
        val senderUin: Long,
        val outgoing: Boolean,
        val text: String,
        val image: UiImage?,
        val voice: UiVoice?,
        val reply: ReplyPreview?,
        val sendStatus: Int,
    )

    data class ChatTarget(
        val chatType: Int,
        val peerUid: String,
        val peerUin: Long,
        val title: String,
        val avatarPath: String,
        val avatarUrl: String,
    ) {
        fun toKernelContact(): Contact = Contact(chatType, peerUid, "")

        companion object {
            fun fromRecent(contact: RecentContactInfo): ChatTarget = ChatTarget(
                chatType = contact.chatType,
                peerUid = contact.peerUid.orEmpty().ifBlank { contact.id.orEmpty() },
                peerUin = contact.peerUin,
                title = contact.peerName.orEmpty().ifBlank {
                    contact.remark.orEmpty().ifBlank {
                        contact.id.orEmpty().ifBlank { contact.peerUin.toString() }
                    }
                },
                avatarPath = contact.avatarPath.orEmpty(),
                avatarUrl = contact.avatarUrl.orEmpty(),
            )
        }
    }

    private val repository = ChatRepository()
    private val messageTable = LinkedHashMap<Long, UiMessage>()
    private val syntheticIds = AtomicLong(-1L)

    private val _target = MutableStateFlow<ChatTarget?>(null)
    val target: StateFlow<ChatTarget?> = _target.asStateFlow()

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _statusText = MutableStateFlow("正在连接消息服务…")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadingOlder = MutableStateFlow(false)
    val loadingOlder: StateFlow<Boolean> = _loadingOlder.asStateFlow()

    private val _hasOlder = MutableStateFlow(true)
    val hasOlder: StateFlow<Boolean> = _hasOlder.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _messageActionInProgress = MutableStateFlow(false)
    val messageActionInProgress: StateFlow<Boolean> = _messageActionInProgress.asStateFlow()

    private val _pendingReply = MutableStateFlow<ReplyTarget?>(null)
    val pendingReply: StateFlow<ReplyTarget?> = _pendingReply.asStateFlow()

    private val _pendingForwardIds = MutableStateFlow<List<Long>>(emptyList())
    val pendingForwardIds: StateFlow<List<Long>> = _pendingForwardIds.asStateFlow()

    private val _multiSelectMode = MutableStateFlow(false)
    val multiSelectMode: StateFlow<Boolean> = _multiSelectMode.asStateFlow()

    private val _selectedMsgIds = MutableStateFlow<LinkedHashSet<Long>>(linkedSetOf())
    val selectedMsgIds: StateFlow<LinkedHashSet<Long>> = _selectedMsgIds.asStateFlow()

    val voicePlaybackStates: StateFlow<Map<Long, PttPlaybackState>> = OfficialPttPlayer.states

    private val pttPlayedMessageIds = ConcurrentHashMap.newKeySet<Long>()

    private var openJob: Job? = null
    private var stateJob: Job? = null

    @Volatile
    private var opening = false

    @Volatile
    private var resyncing = false

    @Volatile
    private var sessionGeneration = 0L
    private var selfUin = 0L

    fun openChat(target: ChatTarget, accountUin: String) {
        val session = RuntimeCoordinator.currentSession()
        val targetChanged = _target.value != target
        if (!targetChanged && session?.generation == sessionGeneration && repository.isConnected()) return

        closeChat(clearTarget = false)
        _target.value = target
        selfUin = accountUin.toLongOrNull() ?: 0L
        sessionGeneration = session?.generation ?: 0L
        _messages.value = emptyList()
        messageTable.clear()
        _loading.value = true
        _hasOlder.value = true
        _statusText.value = "正在连接消息服务…"

        opening = true
        openJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val runtime = RuntimeCoordinator.currentRuntime()
                when (val connection = repository.connect(runtime)) {
                    ChatRepository.Connection.Ready -> {
                        RuntimeCoordinator.currentSession()
                            ?.let { sessionGeneration = it.generation }
                        if (!isCurrentSession()) {
                            failOpen("登录会话已变化，请重新打开聊天")
                            return@launch
                        }
                        registerListener(target)
                        loadLatest(target)
                    }

                    ChatRepository.Connection.KernelUnavailable -> failOpen("QQ 内核尚未就绪")
                    is ChatRepository.Connection.MessageServiceUnavailable -> failOpen(
                        if (connection.timedOut) "消息服务连接超时" else "消息服务不可用",
                    )
                }
            } finally {
                opening = false
            }
        }

        stateJob?.cancel()
        stateJob = viewModelScope.launch {
            RuntimeCoordinator.state.collectLatest {
                val active = _target.value ?: return@collectLatest
                if (RuntimeCoordinator.currentRuntime() == null) return@collectLatest
                resyncIfNeeded(active)
            }
        }
    }

    fun retry() {
        val current = _target.value ?: return
        val account = selfUin.takeIf { it > 0L }?.toString().orEmpty()
        closeChat(clearTarget = false)
        sessionGeneration = 0L
        openChat(current, account)
    }

    /**
     * Recover a live chat after the embedded runtime's generation changed or the
     * kernel message service was replaced.  Without this, the passive listener
     * and the per-generation service cache go stale and the screen silently
     * stops refreshing while sends fail with "service unavailable".
     */
    private fun resyncIfNeeded(target: ChatTarget) {
        if (opening || resyncing) return
        if (_target.value != target) return
        val currentGeneration = RuntimeCoordinator.currentSession()?.generation ?: 0L
        val healthy = currentGeneration == sessionGeneration && repository.isConnected()
        if (healthy) return

        resyncing = true
        openJob?.cancel()
        openJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val runtime = RuntimeCoordinator.currentRuntime()
                if (repository.connect(runtime) is ChatRepository.Connection.Ready) {
                    RuntimeCoordinator.currentSession()?.let { sessionGeneration = it.generation }
                    if (_target.value != target) return@launch
                    registerListener(target)
                    loadLatest(target)
                }
            } finally {
                resyncing = false
            }
        }
    }

    fun loadOlder() {
        if (_loadingOlder.value || !_hasOlder.value) return
        val target = _target.value ?: return
        val oldest = _messages.value.firstOrNull { it.messageId > 0L } ?: return
        _loadingOlder.value = true
        val started = repository.loadOlder(
            ChatRepository.HistoryRequest(
                contact = target.toKernelContact(),
                anchorMessageId = oldest.messageId,
                anchorMessageTime = oldest.timestampSeconds,
                count = HISTORY_COUNT,
            ),
        ) { code, errorMessage, status, records ->
            if (!isCurrentTarget(target)) return@loadOlder
            _loadingOlder.value = false
            if (code != 0) {
                _statusText.value = errorMessage?.takeIf(String::isNotBlank) ?: "更早消息加载失败"
                return@loadOlder
            }
            val usable = records.orEmpty().filter { matchesTarget(it, target) }
            mergeRecords(usable)
            _hasOlder.value = usable.isNotEmpty() && status?.name != "KDONE"
            _statusText.value = when {
                usable.isEmpty() || !_hasOlder.value -> "没有更早的消息了"
                else -> ""
            }
        }
        if (!started) {
            _loadingOlder.value = false
            _statusText.value = "消息服务不可用"
        }
    }

    fun sendText(text: String): Boolean {
        val target = _target.value ?: return false
        val normalized = text.trim()
        if (normalized.isBlank() || _sending.value) return false
        val reply = _pendingReply.value
        _sending.value = true
        _statusText.value = "正在发送…"

        val elements = ArrayList<MsgElement>()
        if (reply != null) {
            val replyElement = createReplyElement(reply)
            if (replyElement == null) {
                _sending.value = false
                _statusText.value = "回复消息不可用"
                return false
            }
            elements.add(replyElement)
        }
        elements.add(
            MsgElement().apply {
                elementType = 1
                elementId = 0L
                textElement = TextElement().apply {
                    content = normalized
                    atType = 0
                    atUid = 0L
                    atNtUid = ""
                }
            },
        )

        val started = repository.sendMessage(target.toKernelContact(), elements) { code, errorMessage ->
            Log.i(
                TAG,
                "sendText result code=$code msg=$errorMessage " +
                        "sameTarget=${isCurrentTarget(target)} connected=${repository.isConnected()}",
            )
            if (!isCurrentTarget(target)) return@sendMessage
            _sending.value = false
            if (code == 0) {
                _pendingReply.value = null
                _statusText.value = ""
            } else {
                _statusText.value =
                    errorMessage?.takeIf(String::isNotBlank)?.let { "发送失败：$it" } ?: "发送失败"
            }
        }
        Log.i(
            TAG,
            "sendText started=$started connected=${repository.isConnected()} " +
                    "gen=$sessionGeneration curGen=${RuntimeCoordinator.currentSession()?.generation} " +
                    "peer=${target.peerUid} type=${target.chatType} reply=${reply != null}",
        )
        if (!started) {
            _sending.value = false
            _statusText.value = "消息服务未就绪，正在重连，请重试"
            resyncIfNeeded(target)
        }
        return started
    }

    fun prepareReply(message: UiMessage): Boolean {
        if (message.messageId <= 0L) {
            _statusText.value = "这条消息暂时无法回复"
            return false
        }
        val target = ReplyTarget(
            messageId = message.messageId,
            senderUid = message.senderUid.ifBlank { message.senderUin.toString() },
            senderName = message.senderName.ifBlank { "对方" },
            summary = message.text.replace('\n', ' ').take(80).ifBlank {
                if (message.image != null) "[图片]" else "[消息]"
            },
        )
        if (createReplyElement(target) == null) {
            _statusText.value = "回复消息不可用"
            return false
        }
        _pendingReply.value = target
        return true
    }

    fun clearPendingReply() {
        _pendingReply.value = null
    }

    fun prepareForward(message: UiMessage): Boolean {
        if (message.messageId <= 0L) {
            _statusText.value = "这条消息暂时无法转发"
            return false
        }
        _pendingForwardIds.value = listOf(message.messageId)
        return true
    }

    fun clearPendingForward() {
        _pendingForwardIds.value = emptyList()
    }

    fun enterMultiSelect(seedMsgId: Long) {
        _multiSelectMode.value = true
        _selectedMsgIds.value = linkedSetOf(seedMsgId)
    }

    fun exitMultiSelect() {
        _multiSelectMode.value = false
        _selectedMsgIds.value = linkedSetOf()
    }

    fun toggleSelection(msgId: Long) {
        if (!_multiSelectMode.value || msgId <= 0L) return
        val next = LinkedHashSet(_selectedMsgIds.value)
        if (!next.add(msgId)) next.remove(msgId)
        _selectedMsgIds.value = next
        if (next.isEmpty()) exitMultiSelect()
    }

    fun prepareBatchForward(): Boolean {
        val ids = _selectedMsgIds.value.filter { it > 0L }
        if (ids.isEmpty()) {
            _statusText.value = "请先选择消息"
            return false
        }
        _pendingForwardIds.value = ids
        return true
    }

    fun batchDeleteSelected(): Boolean {
        val target = _target.value ?: return false
        val ids = _selectedMsgIds.value.filter { it > 0L }
        if (ids.isEmpty() || _messageActionInProgress.value) return false
        _messageActionInProgress.value = true
        _statusText.value = "正在删除 ${ids.size} 条消息…"
        val started = repository.deleteMessages(target.toKernelContact(), ids) { code, errorMessage ->
            if (!isCurrentTarget(target)) return@deleteMessages
            _messageActionInProgress.value = false
            if (code == 0) {
                removeMessagesById(ids)
                exitMultiSelect()
                _statusText.value = ""
            } else {
                _statusText.value =
                    errorMessage?.takeIf(String::isNotBlank)?.let { "删除失败：$it" } ?: "删除失败"
            }
        }
        if (!started) {
            _messageActionInProgress.value = false
            _statusText.value = "消息服务不可用，删除失败"
        }
        return started
    }

    fun forwardPendingTo(chatType: Int, peerUid: String): Boolean {
        val from = _target.value ?: return false
        val ids = _pendingForwardIds.value
        if (ids.isEmpty() || peerUid.isBlank()) return false
        if (_messageActionInProgress.value) return false
        _messageActionInProgress.value = true
        _statusText.value = if (ids.size == 1) "正在转发…" else "正在转发 ${ids.size} 条消息…"
        val to = Contact(chatType, peerUid, "")
        val started = repository.forwardMessages(
            ids,
            from.toKernelContact(),
            to,
        ) { code, errorMessage ->
            if (!isCurrentTarget(from)) return@forwardMessages
            _messageActionInProgress.value = false
            _pendingForwardIds.value = emptyList()
            if (code == 0) exitMultiSelect()
            _statusText.value = if (code == 0) {
                if (ids.size == 1) "已转发" else "已转发 ${ids.size} 条消息"
            } else {
                errorMessage?.takeIf(String::isNotBlank)?.let { "转发失败：$it" } ?: "转发失败"
            }
        }
        if (!started) {
            _messageActionInProgress.value = false
            _statusText.value = "消息服务不可用，转发失败"
        }
        return started
    }

    fun sendVoice(recordedFile: File, durationMillis: Long, formatType: Int): Boolean {
        val target = _target.value ?: return false
        if (_sending.value || !repository.isConnected()) {
            _statusText.value = "消息服务不可用，暂时无法发送语音"
            return false
        }
        if (!recordedFile.isFile || recordedFile.length() <= 0L) {
            _statusText.value = "录音文件无效"
            return false
        }
        _sending.value = true
        _statusText.value = "正在发送语音…"
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val sourceMd5 = md5File(recordedFile)
                val sendPath = repository.getMobileQQSendPath(
                    RichMediaFilePathInfo(
                        4,
                        3,
                        sourceMd5,
                        recordedFile.name,
                        1,
                        0,
                        null,
                        "",
                        true,
                    ),
                ).orEmpty()
                val kernelFile = sendPath.takeIf(String::isNotBlank)?.let(::File)
                val sendFile = if (kernelFile != null) {
                    if (!kernelFile.isFile || kernelFile.length() <= 0L) {
                        kernelFile.parentFile?.mkdirs()
                        recordedFile.copyTo(kernelFile, overwrite = true)
                    }
                    kernelFile
                } else {
                    recordedFile
                }
                if (!sendFile.isFile || sendFile.length() <= 0L) {
                    error("内核语音文件不可用")
                }
                val ptt = PttElement().apply {
                    fileName = sendFile.name
                    filePath = sendFile.absolutePath
                    md5HexStr = md5File(sendFile)
                    fileSize = sendFile.length()
                    duration = max(1, round(durationMillis / 1000.0).toInt())
                    this.formatType = formatType
                    voiceType = 2
                    voiceChangeType = 0
                    canConvert2Text = false
                    fileId = 0
                    fileUuid = ""
                    text = ""
                    waveAmplitudes = arrayListOf()
                }
                MsgElement().apply {
                    elementType = 4
                    elementId = 0L
                    pttElement = ptt
                }
            }
            val element = result.getOrElse { error ->
                Log.w(TAG, "prepare voice failed", error)
                if (isCurrentTarget(target)) {
                    _sending.value = false
                    _statusText.value = error.message?.takeIf(String::isNotBlank)
                        ?.let { "发送语音失败：$it" } ?: "发送语音失败"
                }
                return@launch
            }
            if (!isCurrentTarget(target)) return@launch
            val started = repository.sendMessage(
                target.toKernelContact(),
                arrayListOf(element),
            ) { code, errorMessage ->
                if (!isCurrentTarget(target)) return@sendMessage
                _sending.value = false
                _statusText.value = if (code == 0) {
                    ""
                } else {
                    errorMessage?.takeIf(String::isNotBlank)
                        ?.let { "发送语音失败：$it" } ?: "发送语音失败"
                }
            }
            if (!started) {
                _sending.value = false
                _statusText.value = "消息服务不可用，发送语音失败"
            }
        }
        return true
    }

    fun toggleVoicePlayback(voice: UiVoice) {
        val target = _target.value ?: return
        val localPathAvailable = RichMediaRepository.resolvePttPath(voice.media).isNullOrBlank().not()
        OfficialPttPlayer.toggle(voice.media) {
            RichMediaRepository.requestPttAudio(
                messageId = voice.media.messageId,
                peerUid = target.peerUid,
                chatType = target.chatType,
                elementId = voice.media.elementId,
            )
        }
        if (!localPathAvailable) return
        if (voice.media.playState == 1 || !pttPlayedMessageIds.add(voice.media.messageId)) return
        val previous = voice.media.playState
        val requested = repository.markPttPlayed(
            contact = target.toKernelContact(),
            messageId = voice.media.messageId,
            elementId = voice.media.elementId,
        ) { errorCode, _ ->
            if (errorCode != 0) {
                voice.media.playState = previous
                voice.media.pttElement?.playState = previous
                pttPlayedMessageIds.remove(voice.media.messageId)
            }
        }
        if (!requested) {
            voice.media.playState = previous
            voice.media.pttElement?.playState = previous
            pttPlayedMessageIds.remove(voice.media.messageId)
        } else {
            voice.media.playState = 1
            voice.media.pttElement?.playState = 1
        }
    }

    fun searchLoaded(query: String): List<UiMessage> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return _messages.value.filter {
            it.text.contains(needle, ignoreCase = true) ||
                    it.senderName.contains(needle, ignoreCase = true)
        }
    }

    private fun createReplyElement(target: ReplyTarget): MsgElement? = runCatching {
        QRoute.api(IMsgUtilApi::class.java)
            .createReplyElement(target.messageId)
            .also { element ->
                element.replyElement?.senderUid = target.senderUid.toLongOrNull() ?: 0L
                element.replyElement?.senderUidStr = target.senderUid
            }
    }.onFailure {
        Log.w(TAG, "create reply element failed msg=${target.messageId}", it)
    }.getOrNull()

    fun sendImage(context: Context, uri: Uri): Boolean {
        val target = _target.value ?: return false
        if (_sending.value || !repository.isConnected()) {
            _statusText.value = "消息服务不可用，暂时无法发送图片"
            return false
        }
        _sending.value = true
        _statusText.value = "正在准备图片…"
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { buildImageElement(context.applicationContext, uri) }
            val element = result.getOrElse { error ->
                Log.w(TAG, "prepare image failed", error)
                if (isCurrentTarget(target)) {
                    _sending.value = false
                    _statusText.value = error.message?.takeIf(String::isNotBlank)
                        ?.let { "读取图片失败：$it" } ?: "读取图片失败"
                }
                return@launch
            }
            if (!isCurrentTarget(target)) return@launch
            _statusText.value = "正在发送图片…"
            val started = repository.sendMessage(
                target.toKernelContact(),
                arrayListOf(element)
            ) { code, errorMessage ->
                if (!isCurrentTarget(target)) return@sendMessage
                _sending.value = false
                _statusText.value = if (code == 0) {
                    ""
                } else {
                    errorMessage?.takeIf(String::isNotBlank)
                        ?.let { "发送图片失败：$it" } ?: "发送图片失败"
                }
            }
            if (!started) {
                _sending.value = false
                _statusText.value = "消息服务不可用，发送图片失败"
            }
        }
        return true
    }

    fun recallMessage(message: UiMessage): Boolean {
        val target = _target.value ?: return false
        if (!message.outgoing || message.messageId <= 0L || _messageActionInProgress.value) {
            return false
        }
        _messageActionInProgress.value = true
        _statusText.value = "正在撤回消息…"
        val started = repository.recallMessage(
            target.toKernelContact(),
            message.messageId
        ) { code, errorMessage ->
            if (!isCurrentTarget(target)) return@recallMessage
            _messageActionInProgress.value = false
            if (code == 0) {
                // The kernel normally emits an update/gray tip afterwards. Keep
                // the row stable in the meantime instead of leaving stale rich
                // media visible after a successful recall.
                messageTable[message.stableId] = message.copy(
                    text = "你撤回了一条消息",
                    image = null,
                    voice = null,
                    reply = null,
                    sendStatus = 2,
                )
                publishMessages()
                _statusText.value = ""
            } else {
                _statusText.value = errorMessage?.takeIf(String::isNotBlank)
                    ?.let { "撤回失败：$it" } ?: "撤回失败"
            }
        }
        if (!started) {
            _messageActionInProgress.value = false
            _statusText.value = "消息服务不可用，撤回失败"
        }
        return started
    }

    fun deleteMessage(message: UiMessage): Boolean {
        val target = _target.value ?: return false
        if (message.messageId <= 0L || _messageActionInProgress.value) return false
        _messageActionInProgress.value = true
        _statusText.value = "正在删除消息…"
        val started = repository.deleteMessages(
            target.toKernelContact(),
            listOf(message.messageId)
        ) { code, errorMessage ->
            if (!isCurrentTarget(target)) return@deleteMessages
            _messageActionInProgress.value = false
            if (code == 0) {
                removeMessagesById(listOf(message.messageId))
                _statusText.value = ""
            } else {
                _statusText.value = errorMessage?.takeIf(String::isNotBlank)
                    ?.let { "删除失败：$it" } ?: "删除失败"
            }
        }
        if (!started) {
            _messageActionInProgress.value = false
            _statusText.value = "消息服务不可用，删除失败"
        }
        return started
    }

    fun resendMessage(message: UiMessage): Boolean {
        val target = _target.value ?: return false
        if (!message.outgoing || message.messageId <= 0L || _messageActionInProgress.value) {
            return false
        }
        _messageActionInProgress.value = true
        _statusText.value = "正在重发消息…"
        messageTable[message.stableId] = message.copy(sendStatus = 1)
        publishMessages()
        val started = repository.resendMessage(
            target.toKernelContact(),
            message.messageId
        ) { code, errorMessage ->
            if (!isCurrentTarget(target)) return@resendMessage
            _messageActionInProgress.value = false
            _statusText.value = if (code == 0) {
                ""
            } else {
                errorMessage?.takeIf(String::isNotBlank)?.let { "重发失败：$it" } ?: "重发失败"
            }
        }
        if (!started) {
            _messageActionInProgress.value = false
            _statusText.value = "消息服务不可用，重发失败"
        }
        return started
    }

    fun closeChat(clearTarget: Boolean = true) {
        openJob?.cancel()
        openJob = null
        stateJob?.cancel()
        stateJob = null
        opening = false
        resyncing = false
        repository.close()
        _loading.value = false
        _loadingOlder.value = false
        _sending.value = false
        _messageActionInProgress.value = false
        _pendingReply.value = null
        _pendingForwardIds.value = emptyList()
        exitMultiSelect()
        OfficialPttPlayer.stopAndRelease()
        if (clearTarget) {
            _target.value = null
            messageTable.clear()
            _messages.value = emptyList()
        }
    }

    private fun loadLatest(target: ChatTarget) {
        val started = repository.loadLatest(
            target.toKernelContact(),
            LATEST_COUNT
        ) { code, errorMessage, records, needContinue ->
            if (!isCurrentTarget(target)) return@loadLatest
            _loading.value = false
            if (code != 0) {
                _statusText.value = errorMessage?.takeIf(String::isNotBlank) ?: "消息加载失败"
                return@loadLatest
            }
            val usable = records.orEmpty().filter { matchesTarget(it, target) }
            mergeRecords(usable)
            _hasOlder.value = needContinue || usable.size >= LATEST_COUNT
            _statusText.value = if (usable.isEmpty()) "还没有消息，发一条试试" else ""
            repository.markMessagesRead(target.toKernelContact()) { readCode, readError ->
                if (readCode != 0) Log.w(TAG, "mark read failed: $readError")
            }
        }
        if (!started) {
            failOpen("消息服务不可用")
            resyncIfNeeded(target)
        }
    }

    private fun registerListener(target: ChatTarget) {
        repository.startListening(object : ChatRepository.Listener {
            override fun onReceived(messages: ArrayList<MsgRecord>) {
                val usable = messages.filter { matchesTarget(it, target) }
                if (usable.isEmpty() || !isCurrentTarget(target)) return
                mergeRecords(usable)
                usable.lastOrNull()?.let { RecentMessageStore.put(target.peerUid, it) }
                repository.markMessagesRead(target.toKernelContact())
            }

            override fun onAddedSendMessage(message: MsgRecord) {
                if (!matchesTarget(message, target) || !isCurrentTarget(target)) return
                mergeRecords(listOf(message))
                RecentMessageStore.put(target.peerUid, message)
            }

            override fun onMessageUpdated(messages: ArrayList<MsgRecord>) {
                val usable = messages.filter { matchesTarget(it, target) }
                if (usable.isNotEmpty() && isCurrentTarget(target)) mergeRecords(usable)
            }

            override fun onMessageDeleted(contact: Contact, messageIds: ArrayList<Long>) {
                if (contact.chatType != target.chatType || contact.peerUid != target.peerUid) return
                removeMessagesById(messageIds)
            }
        })
    }

    @Synchronized
    private fun mergeRecords(records: Collection<MsgRecord>) {
        records.forEach { record ->
            val ui = record.toUiMessage()
            messageTable[ui.stableId] = ui
        }
        publishMessages()
    }

    @Synchronized
    private fun removeMessagesById(messageIds: Collection<Long>) {
        val ids = messageIds.toSet()
        if (ids.isEmpty()) return
        val iterator = messageTable.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in ids || entry.value.messageId in ids) iterator.remove()
        }
        publishMessages()
    }

    @Synchronized
    private fun publishMessages() {
        _messages.value = messageTable.values.sortedWith(
            compareBy<UiMessage> { it.timestampSeconds }
                .thenBy { it.sequence }
                .thenBy { it.stableId },
        )
    }

    private fun MsgRecord.toUiMessage(): UiMessage {
        val stable = when {
            msgId > 0L -> msgId
            clientSeq > 0L -> clientSeq
            msgSeq > 0L -> msgSeq
            else -> syntheticIds.getAndDecrement()
        }
        val elems = elements.orEmpty()
        val reply = elems.firstNotNullOfOrNull { it.replyElement }?.let { reply ->
            val summary = reply.sourceMsgText?.takeIf { it.isNotBlank() }
                ?: if (reply.sourceMsgIsIncPic) "[图片]" else "原消息"
            ReplyPreview(
                senderName = reply.anonymousNickName?.takeIf { it.isNotBlank() }
                    ?: reply.senderUidStr?.takeIf { it.isNotBlank() && it != "0" }
                    ?: reply.senderUid?.takeIf { it > 0L }?.toString()
                    ?: "对方",
                summary = summary.replace('\n', ' ').take(80),
            )
        }
        val voiceElement = elems.firstOrNull { it.pttElement != null }
        val ptt = voiceElement?.pttElement
        val voice = ptt?.let {
            UiVoice(
                media = PttMediaRef(
                    messageId = msgId,
                    elementId = voiceElement.elementId,
                    filePath = it.filePath,
                    md5Hex = it.md5HexStr,
                    fileName = it.fileName,
                    importRichMediaContext = null,
                    fileUuid = it.fileUuid,
                    durationSeconds = it.duration.coerceAtLeast(1),
                    playState = it.playState,
                    pttElement = it,
                    msgElement = voiceElement,
                ),
                durationSeconds = it.duration.coerceAtLeast(1),
            )
        }
        val body = elems.asSequence()
            .filter { it.replyElement == null && it.pttElement == null }
            .joinToString(separator = "") { it.displayText() }
            .ifBlank {
                when {
                    voice != null -> "[语音] ${voice.durationSeconds}\""
                    reply != null -> ""
                    else -> "[暂不支持的消息]"
                }
            }
        return UiMessage(
            stableId = stable,
            messageId = msgId,
            sequence = msgSeq,
            timestampSeconds = msgTime.takeIf { it > 0L } ?: timeStamp,
            senderName = sendRemarkName.orEmpty().ifBlank {
                sendMemberName.orEmpty().ifBlank {
                    sendNickName.orEmpty().ifBlank {
                        senderUin.takeIf { it > 0L }?.toString().orEmpty()
                    }
                }
            },
            senderUid = senderUid.orEmpty(),
            outgoing = selfUin > 0L && senderUin == selfUin,
            senderUin = senderUin,
            text = body,
            image = elems.firstNotNullOfOrNull { it.picElement }?.toUiImage(),
            voice = voice,
            reply = reply,
            sendStatus = sendStatus,
        )
    }

    private fun PicElement.toUiImage(): UiImage = UiImage(
        localPaths = buildList {
            thumbPath?.values?.filterTo(this) { it.isNotBlank() }
            sourcePath?.takeIf(String::isNotBlank)?.let(::add)
        }.distinct(),
        remoteUrls = listOfNotNull(
            originImageUrl?.takeIf(String::isNotBlank),
            emojiWebUrl?.takeIf(String::isNotBlank),
        ).distinct(),
        width = picWidth,
        height = picHeight,
    )

    private fun buildImageElement(context: Context, uri: Uri): MsgElement {
        val displayName = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf(String::isNotBlank) ?: "image_${System.currentTimeMillis()}.jpg"
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val temporary = File(context.cacheDir, "send_${System.currentTimeMillis()}_$safeName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            temporary.outputStream().use(input::copyTo)
        } ?: error("无法打开所选图片")
        if (temporary.length() <= 0L) error("图片文件为空")

        val md5 = md5File(temporary)
        val originalPath = repository.getMobileQQSendPath(
            RichMediaFilePathInfo(2, 0, md5, safeName, 1, 0, null, "", true),
        ) ?: temporary.absolutePath
        if (originalPath != temporary.absolutePath) {
            File(originalPath).also { it.parentFile?.mkdirs() }
            temporary.copyTo(File(originalPath), overwrite = true)
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(originalPath, bounds)
        val extension = safeName.substringAfterLast('.', "").lowercase()
        val type = when (extension) {
            "jpg", "jpeg" -> 1000
            "png" -> 1001
            "webp" -> 1002
            "gif" -> 2000
            "bmp" -> 1005
            else -> 1001
        }
        val picture = PicElement().apply {
            sourcePath = originalPath
            fileName = safeName
            fileSize = File(originalPath).length()
            md5HexStr = md5
            picWidth = bounds.outWidth.takeIf { it > 0 } ?: 800
            picHeight = bounds.outHeight.takeIf { it > 0 } ?: 600
            picType = type
            picSubType = 0
            original = true
            storeID = 0
        }
        return MsgElement().apply {
            elementType = 2
            elementId = 0L
            picElement = picture
        }
    }

    private fun md5File(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun MsgElement.displayText(): String = when {
        textElement != null -> textElement.content.orEmpty()
        picElement != null -> "[图片]"
        pttElement != null -> "[语音]"
        videoElement != null -> "[视频]"
        fileElement != null -> "[文件]"
        faceElement != null || marketFaceElement != null -> "[表情]"
        replyElement != null -> "[回复]"
        multiForwardMsgElement != null -> "[聊天记录]"
        arkElement != null || structMsgElement != null || structLongMsgElement != null -> "[卡片消息]"
        grayTipElement != null -> grayTipElement.toString()
        else -> ""
    }

    private fun matchesTarget(record: MsgRecord, target: ChatTarget): Boolean {
        if (record.chatType != target.chatType) return false
        return record.peerUid.orEmpty() == target.peerUid ||
                (target.peerUin > 0L && record.peerUin == target.peerUin)
    }

    private fun isCurrentSession(): Boolean =
        sessionGeneration > 0L && RuntimeCoordinator.currentSession()?.generation == sessionGeneration

    private fun isCurrentTarget(target: ChatTarget): Boolean =
        _target.value == target

    private fun failOpen(message: String) {
        _loading.value = false
        _statusText.value = message
    }

    override fun onCleared() {
        closeChat()
        super.onCleared()
    }

    private companion object {
        const val TAG = "QMME-Chat"
        const val LATEST_COUNT = 30
        const val HISTORY_COUNT = 20
    }
}
