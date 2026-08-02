package rj.qmme.data.chat

import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.PttElement

data class RichMediaKey(
    val messageId: Long,
    val elementId: Long,
)

sealed interface RichMediaRequestState {
    data object Idle : RichMediaRequestState
    data object Loading : RichMediaRequestState
    data class Failed(val message: String) : RichMediaRequestState
}

data class PttMediaRef(
    val messageId: Long,
    val elementId: Long,
    val filePath: String?,
    val md5Hex: String?,
    val fileName: String?,
    val importRichMediaContext: ByteArray?,
    val fileUuid: String?,
    val durationSeconds: Int,
    var playState: Int?,
    val pttElement: PttElement? = null,
    val msgElement: MsgElement? = null,
)

enum class PttPlaybackPhase {
    Idle,
    Preparing,
    Playing,
    Paused,
    Failed,
}

data class PttPlaybackState(
    val messageId: Long,
    val phase: PttPlaybackPhase = PttPlaybackPhase.Idle,
    val positionMillis: Int = 0,
    val durationMillis: Int = 0,
    val error: String? = null,
)
