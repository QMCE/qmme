package rj.qmme.ui

import android.content.res.ColorStateList
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.highcapable.betterandroid.ui.extension.view.toast
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.progressindicator.CircularProgressIndicator
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView
import com.tencent.mobileqq.ptt.IQQRecorder
import com.tencent.mobileqq.ptt.IQQRecorderUtils
import com.tencent.mobileqq.qroute.QRoute
import com.tencent.mobileqq.utils.RecordParams
import com.tencent.mobileqq.utils.RecordParams.RecorderParam
import com.tencent.qqnt.watch.ptt.AudioFileWriterNT
import com.tencent.qqnt.watch.ptt.PttRecordCallback
import mqq.app.MobileQQ
import rj.qmme.R
import rj.qmme.data.emotion.EmotionSdkAccess
import rj.qmme.ui.hikage.FilledIconButton
import rj.qmme.ui.hikage.OutlinedButton
import java.io.File

/** Full-screen Material 3 voice recorder for phone chat. */
class VoiceRecordHikagable(
    private val context: Context,
    private val onBack: () -> Unit,
    private val onSendVoice: (file: File, durationMillis: Long, formatType: Int) -> Unit,
) : HikageScreen {
    private sealed interface VoiceRecordState {
        data object Idle : VoiceRecordState
        data object Recording : VoiceRecordState
        data object Finalizing : VoiceRecordState
        data class Ready(
            val file: File,
            val pcmFile: File,
            val durationMillis: Long,
            val formatType: Int,
        ) : VoiceRecordState

        data class Error(val message: String, val needsPermission: Boolean = false) : VoiceRecordState
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recorder: IQQRecorder? = null
    private var recordState: VoiceRecordState = VoiceRecordState.Idle
    private var elapsedMillis: Long = 0L
    private var startedAt: Long = 0L
    private var discardWhenFinished = false
    private var keepScreenOn = false

    private lateinit var statusText: MaterialTextView
    private lateinit var elapsedText: MaterialTextView
    private lateinit var primaryButton: MaterialButton
    private lateinit var progressIndicator: CircularProgressIndicator
    private lateinit var secondaryRow: LinearLayout
    private lateinit var rerecordButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var authButton: MaterialButton

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (recordState !is VoiceRecordState.Recording) return
            elapsedMillis = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            updateElapsedDisplay()
            if (elapsedMillis >= 60_000L) {
                stopRecording(discard = false)
            } else {
                mainHandler.postDelayed(this, 100L)
            }
        }
    }

    override val hikage: Hikage.Delegate<*> = Hikagable {
        LinearLayout(
            lparams = LayoutParams(matchParent = true),
            init = {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorSurface,
                    ),
                )
            },
        ) {
            LinearLayout(
                lparams = LayoutParams(widthMatchParent = true, height = 0),
                init = { EdgeToEdgeInsets.applyTopInsetSpacer(this) },
            )
            MaterialToolbar(
                lparams = LayoutParams(widthMatchParent = true),
                init = {
                    title = "语音消息"
                    navigationIcon = drawableResource(R.drawable.ic_arrow_back)
                    setNavigationContentDescription("返回")
                    setNavigationOnClickListener { handleBack() }
                    EdgeToEdgeInsets.applyHorizontalInsets(this)
                },
            )
            LinearLayout(
                lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
                init = {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(24), dp(16), dp(24), dp(16))
                    EdgeToEdgeInsets.applyHorizontalInsets(this)
                },
            ) {
                statusText = MaterialTextView(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        TextViewCompat.setTextAppearance(
                            this,
                            com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium,
                        )
                        gravity = Gravity.CENTER
                    },
                )
                elapsedText = MaterialTextView(
                    lparams = LayoutParams(widthMatchParent = true) { topMargin = dp(8) },
                    init = {
                        TextViewCompat.setTextAppearance(
                            this,
                            com.google.android.material.R.style.TextAppearance_Material3_DisplaySmall,
                        )
                        gravity = Gravity.CENTER
                        visibility = View.GONE
                    },
                )
                progressIndicator = CircularProgressIndicator(
                    lparams = LayoutParams(width = dp(48), height = dp(48)) { topMargin = dp(24) },
                    init = {
                        isIndeterminate = true
                        visibility = View.GONE
                    },
                )
                primaryButton = FilledIconButton(
                    lparams = LayoutParams(width = dp(72), height = dp(72)) { topMargin = dp(24) },
                    init = {
                        icon = drawableResource(R.drawable.ic_mic)
                        contentDescription = "开始录音"
                        setOnClickListener { onPrimaryAction() }
                    },
                )
                secondaryRow = LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true) { topMargin = dp(16) },
                    init = {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                    },
                ) {
                    rerecordButton = OutlinedButton(
                        lparams = LayoutParams(width = 0, height = ViewGroup.LayoutParams.WRAP_CONTENT) {
                            weight = 1f
                            marginEnd = dp(8)
                        },
                        init = {
                            text = "重录"
                            isAllCaps = false
                            icon = drawableResource(R.drawable.ic_refresh)
                            visibility = View.GONE
                            setOnClickListener { resetReadyState() }
                        },
                    )
                    authButton = OutlinedButton(
                        lparams = LayoutParams(width = 0, height = ViewGroup.LayoutParams.WRAP_CONTENT) {
                            weight = 1f
                            marginEnd = dp(8)
                        },
                        init = {
                            text = "去授权"
                            isAllCaps = false
                            visibility = View.GONE
                            setOnClickListener { requestRecordPermission() }
                        },
                    )
                    cancelButton = OutlinedButton(
                        lparams = LayoutParams(width = 0, height = ViewGroup.LayoutParams.WRAP_CONTENT) {
                            weight = 1f
                            marginStart = dp(8)
                        },
                        init = {
                            text = "取消"
                            isAllCaps = false
                            icon = drawableResource(R.drawable.ic_close)
                            setOnClickListener { onCancelAction() }
                        },
                    )
                }
            }
            LinearLayout(
                lparams = LayoutParams(widthMatchParent = true, height = 0),
                init = { EdgeToEdgeInsets.applyBottomInsetSpacer(this) },
            )
        }
    }.also { updateUi() }

    fun dispose() {
        mainHandler.removeCallbacks(tickRunnable)
        if (recordState is VoiceRecordState.Recording || recordState is VoiceRecordState.Finalizing) {
            discardWhenFinished = true
            val activeRecorder = recorder
            recorder = null
            runCatching { activeRecorder?.stop() }
        }
        when (val state = recordState) {
            is VoiceRecordState.Ready -> state.pcmFile.delete()
            else -> Unit
        }
        setKeepScreenOn(false)
    }

    private fun handleBack() {
        dispose()
        onBack()
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestRecordPermission() {
        val activity = context as? Activity
        if (activity == null) {
            context.toast("无法请求录音权限")
            return
        }
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO,
        )
    }

    private fun beginRecording() {
        if (recordState !is VoiceRecordState.Idle && recordState !is VoiceRecordState.Error) return
        if (!hasRecordPermission()) {
            recordState = VoiceRecordState.Error("未获得录音权限", needsPermission = true)
            updateUi()
            return
        }
        runCatching {
            val recorderParam = RecordParams.b(MobileQQ.sMobileQQ.peekAppRuntime(), false)
            val baseDir = context.getExternalFilesDir("audio") ?: File(context.cacheDir, "audio")
            val recordingDir = File(baseDir, if (recorderParam.d == 1) "silk" else "amr")
            recordingDir.mkdirs()
            val outputPath = File(recordingDir, "voice_${System.currentTimeMillis()}").absolutePath
            val pcmPath = File(baseDir, "pcmforvad.pcm").absolutePath

            val callback = object : IQQRecorder.OnQQRecorderListener {
                override fun a() = Unit
                override fun b(path: String?, recorderParam: RecorderParam?) = Unit
                override fun c(): Int = 250
                override fun d(state: Int) = Unit

                override fun e(
                    path: String?,
                    slice: ByteArray?,
                    size: Int,
                    maxAmplitude: Int,
                    time: Double,
                    recorderParam: RecorderParam?,
                ) = Unit

                override fun f(path: String?, recorderParam: RecorderParam?) = Unit
                override fun g(path: String?, recorderParam: RecorderParam?) = Unit

                override fun h(path: String?, recorderParam: RecorderParam?, totalTime: Double) {
                    mainHandler.post {
                        recorder = null
                        val file = path?.let(::File)
                        if (discardWhenFinished) {
                            file?.delete()
                            discardWhenFinished = false
                            recordState = VoiceRecordState.Idle
                            elapsedMillis = 0L
                        } else if (file?.isFile == true && file.length() > 0L) {
                            recordState = VoiceRecordState.Ready(
                                file = file,
                                pcmFile = File(pcmPath),
                                durationMillis = totalTime.toLong().coerceAtLeast(0L),
                                formatType = if (recorderParam?.d == 1) 1 else 0,
                            )
                        } else {
                            recordState = VoiceRecordState.Error("录音文件生成失败")
                        }
                        setKeepScreenOn(false)
                        updateUi()
                    }
                }

                override fun i(path: String?, recorderParam: RecorderParam?, error: String?) {
                    mainHandler.post {
                        if (recordState is VoiceRecordState.Recording ||
                            recordState is VoiceRecordState.Finalizing
                        ) {
                            recorder = null
                            recordState = VoiceRecordState.Error(
                                error?.takeIf(String::isNotBlank) ?: "录音失败",
                            )
                            setKeepScreenOn(false)
                            updateUi()
                        }
                    }
                }

                override fun j(path: String?, recorderParam: RecorderParam?): Int = -1
            }
            val pttCallback = PttRecordCallback(null, AudioFileWriterNT(null)).also { callbackHolder ->
                EmotionSdkAccess.setPttRecordPanel(callbackHolder, callback)
            }
            val newRecorder = QRoute.api(IQQRecorderUtils::class.java).createQQRecorder(context)
            newRecorder.d(recorderParam)
            newRecorder.c(pcmPath)
            newRecorder.f(pttCallback)
            newRecorder.a(outputPath)
            recorder = newRecorder
            discardWhenFinished = false
            startedAt = SystemClock.elapsedRealtime()
            elapsedMillis = 0L
            recordState = VoiceRecordState.Recording
            setKeepScreenOn(true)
            mainHandler.post(tickRunnable)
            updateUi()
        }.onFailure {
            recorder = null
            recordState = VoiceRecordState.Error("无法开始录音: ${it.message ?: "未知错误"}")
            setKeepScreenOn(false)
            updateUi()
        }
    }

    private fun stopRecording(discard: Boolean) {
        if (recordState !is VoiceRecordState.Recording) return
        mainHandler.removeCallbacks(tickRunnable)
        discardWhenFinished = discard
        recordState = VoiceRecordState.Finalizing
        val activeRecorder = recorder
        recorder = null
        updateUi()
        runCatching { activeRecorder?.stop() }.onFailure {
            recordState = VoiceRecordState.Error("无法结束录音: ${it.message ?: "未知错误"}")
            setKeepScreenOn(false)
            updateUi()
        }
    }

    private fun sendReadyVoice(state: VoiceRecordState.Ready) {
        onSendVoice(state.file, state.durationMillis, state.formatType)
        state.pcmFile.delete()
        dispose()
        onBack()
    }

    private fun resetReadyState() {
        val state = recordState as? VoiceRecordState.Ready ?: return
        state.file.delete()
        state.pcmFile.delete()
        recordState = VoiceRecordState.Idle
        elapsedMillis = 0L
        updateUi()
    }

    private fun onPrimaryAction() {
        when (val state = recordState) {
            VoiceRecordState.Idle -> beginRecording()
            is VoiceRecordState.Error -> {
                if (state.needsPermission) requestRecordPermission()
                else beginRecording()
            }
            VoiceRecordState.Recording -> stopRecording(discard = false)
            is VoiceRecordState.Ready -> sendReadyVoice(state)
            VoiceRecordState.Finalizing -> Unit
        }
    }

    private fun onCancelAction() {
        when (recordState) {
            VoiceRecordState.Recording -> stopRecording(discard = true)
            else -> handleBack()
        }
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        if (keepScreenOn == enabled) return
        keepScreenOn = enabled
        val window = (context as? Activity)?.window ?: return
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateElapsedDisplay() {
        elapsedText.text = formatVoiceRecordDuration(elapsedMillis)
    }

    private fun updateUi() {
        if (!::statusText.isInitialized) return

        val hasError = recordState is VoiceRecordState.Error
        statusText.setTextColor(
            MaterialColors.getColor(
                statusText,
                if (hasError) {
                    androidx.appcompat.R.attr.colorError
                } else {
                    com.google.android.material.R.attr.colorOnSurface
                },
            ),
        )

        when (val state = recordState) {
            VoiceRecordState.Idle -> {
                statusText.text = "点击开始"
                elapsedText.visibility = View.GONE
                progressIndicator.visibility = View.GONE
                primaryButton.visibility = View.VISIBLE
                primaryButton.icon = context.getDrawable(R.drawable.ic_mic)
                primaryButton.contentDescription = "开始录音"
                primaryButton.backgroundTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(
                        primaryButton,
                        androidx.appcompat.R.attr.colorPrimary,
                    ),
                )
                primaryButton.iconTint = ColorStateList.valueOf(
                    MaterialColors.getColor(
                        primaryButton,
                        com.google.android.material.R.attr.colorOnPrimary,
                    ),
                )
                rerecordButton.visibility = View.GONE
                authButton.visibility = View.GONE
                cancelButton.visibility = View.VISIBLE
            }

            VoiceRecordState.Recording -> {
                statusText.text = "录音中"
                elapsedText.visibility = View.VISIBLE
                updateElapsedDisplay()
                progressIndicator.visibility = View.GONE
                primaryButton.visibility = View.VISIBLE
                primaryButton.icon = context.getDrawable(R.drawable.ic_stop)
                primaryButton.contentDescription = "结束录音"
                primaryButton.backgroundTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(
                        primaryButton,
                        androidx.appcompat.R.attr.colorError,
                    ),
                )
                primaryButton.iconTint = ColorStateList.valueOf(
                    MaterialColors.getColor(
                        primaryButton,
                        com.google.android.material.R.attr.colorOnError,
                    ),
                )
                rerecordButton.visibility = View.GONE
                authButton.visibility = View.GONE
                cancelButton.visibility = View.VISIBLE
            }

            VoiceRecordState.Finalizing -> {
                statusText.text = "正在保存录音"
                elapsedText.visibility = View.GONE
                progressIndicator.visibility = View.VISIBLE
                primaryButton.visibility = View.GONE
                rerecordButton.visibility = View.GONE
                authButton.visibility = View.GONE
                cancelButton.visibility = View.GONE
            }

            is VoiceRecordState.Ready -> {
                statusText.text = "录音完成"
                elapsedText.visibility = View.VISIBLE
                elapsedText.text = formatVoiceRecordDuration(state.durationMillis)
                progressIndicator.visibility = View.GONE
                primaryButton.visibility = View.VISIBLE
                primaryButton.icon = context.getDrawable(R.drawable.ic_send)
                primaryButton.contentDescription = "发送"
                primaryButton.backgroundTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(
                        primaryButton,
                        androidx.appcompat.R.attr.colorPrimary,
                    ),
                )
                primaryButton.iconTint = ColorStateList.valueOf(
                    MaterialColors.getColor(
                        primaryButton,
                        com.google.android.material.R.attr.colorOnPrimary,
                    ),
                )
                rerecordButton.visibility = View.VISIBLE
                authButton.visibility = View.GONE
                cancelButton.visibility = View.VISIBLE
            }

            is VoiceRecordState.Error -> {
                statusText.text = if (state.needsPermission) {
                    "未获得录音权限"
                } else {
                    state.message
                }
                elapsedText.visibility = View.GONE
                progressIndicator.visibility = View.GONE
                if (state.needsPermission) {
                    primaryButton.visibility = View.GONE
                    authButton.visibility = View.VISIBLE
                } else {
                    primaryButton.visibility = View.VISIBLE
                    primaryButton.icon = context.getDrawable(R.drawable.ic_mic)
                    primaryButton.contentDescription = "重试"
                    primaryButton.backgroundTintList = ColorStateList.valueOf(
                        MaterialColors.getColor(
                            primaryButton,
                            androidx.appcompat.R.attr.colorPrimary,
                        ),
                    )
                    primaryButton.iconTint = ColorStateList.valueOf(
                        MaterialColors.getColor(
                            primaryButton,
                            com.google.android.material.R.attr.colorOnPrimary,
                        ),
                    )
                    authButton.visibility = View.GONE
                }
                rerecordButton.visibility = View.GONE
                cancelButton.visibility = View.VISIBLE
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_RECORD_AUDIO = 9001

        fun formatVoiceRecordDuration(durationMillis: Long): String {
            val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
            return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
        }
    }
}
