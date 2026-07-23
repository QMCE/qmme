package rj.qmme.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.LinearLayout as AndroidLinearLayout
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton as MaterialButtonView
import com.google.android.material.card.MaterialCardView as MaterialCardViewView
import com.google.android.material.checkbox.MaterialCheckBox as MaterialCheckBoxView
import com.google.android.material.imageview.ShapeableImageView as ShapeableImageViewView
import com.google.android.material.progressindicator.CircularProgressIndicator as CircularProgressIndicatorView
import com.google.android.material.textview.MaterialTextView as MaterialTextViewView
import com.google.android.material.radiobutton.MaterialRadioButton as MaterialRadioButtonView
import com.highcapable.hikage.annotation.Hikagable as HikagableAnnotation
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.core.layout.invoke
import com.highcapable.hikage.extension.addView
import com.highcapable.hikage.widget.android.widget.LinearLayout as HLinearLayout
import com.highcapable.hikage.widget.android.widget.ScrollView as HScrollView
import com.highcapable.hikage.widget.android.widget.RadioGroup as HRadioGroup
import com.highcapable.hikage.widget.com.google.android.material.button.MaterialButton as HMaterialButton
import com.highcapable.hikage.widget.com.google.android.material.card.MaterialCardView as HMaterialCardView
import com.highcapable.hikage.widget.com.google.android.material.checkbox.MaterialCheckBox as HMaterialCheckBox
import com.highcapable.hikage.widget.com.google.android.material.imageview.ShapeableImageView as HShapeableImageView
import com.highcapable.hikage.widget.com.google.android.material.progressindicator.CircularProgressIndicator as HCircularProgressIndicator
import com.highcapable.hikage.widget.com.google.android.material.radiobutton.MaterialRadioButton as HMaterialRadioButton
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView as HMaterialTextView
import com.tencent.qphone.base.remote.SimpleAccount
import kotlinx.coroutines.launch
import rj.qmme.R
import rj.qmme.viewmodel.AuthViewModel

/**
 * Complete login screen represented by a Hikage tree.
 *
 * The Activity mounts [root] directly. Step changes replace the nested Hikage
 * content root, while the screen itself remains a non-View controller.
 */
class LoginHikagable(
    private val context: Context,
) : HikageScreen {
    private enum class Step { Welcome, ScreenType, Agreement, Qr }

    private var step = Step.Welcome
    private var viewModel: AuthViewModel? = null
    private var qrBitmap: Bitmap? = null
    private var statusText = "未初始化"
    private var loginUiState: AuthViewModel.LoginUiState = AuthViewModel.LoginUiState.Idle
    private var busy = false
    private var qrImage: ShapeableImageViewView? = null
    private var qrProgress: CircularProgressIndicatorView? = null
    private var qrStatus: MaterialTextViewView? = null
    private var qrRetry: MaterialButtonView? = null
    private lateinit var scrollRoot: ScrollView
    private lateinit var contentRoot: AndroidLinearLayout

    private var cachedHikage: Hikage.Delegate<*>? = null

    override val hikage
        get() = cachedHikage ?: Hikagable {
            // The root ScrollView has to be assigned before showStep() mounts
            // the first nested Hikage tree and schedules its scroll reset.
            scrollRoot = HScrollView(
                lparams = LayoutParams(matchParent = true),
                init = {
                    isFillViewport = true
                    clipToPadding = false
                },
            ) {
                contentRoot = HLinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        orientation = AndroidLinearLayout.VERTICAL
                    },
                )
            }
            showStep(Step.Welcome)
        }.also { cachedHikage = it }

    var onLoginSuccess: ((String, SimpleAccount) -> Unit)? = null

    fun bind(owner: LifecycleOwner, vm: AuthViewModel) {
        viewModel = vm
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.qrBitmap.collect { qrBitmap = it; renderQrState() } }
                launch { vm.statusText.collect { statusText = it; renderQrState() } }
                launch { vm.loginUiState.collect { loginUiState = it; renderQrState() } }
                launch { vm.isBusy.collect { busy = it; renderQrState() } }
                launch {
                    vm.loginResult.collect { (uin, account) ->
                        onLoginSuccess?.invoke(uin, account)
                    }
                }
            }
        }
        vm.initWtService()
    }

    private fun showStep(target: Step) {
        step = target
        qrImage = null
        qrProgress = null
        qrStatus = null
        qrRetry = null
        when (target) {
            Step.Welcome -> buildWelcome()
            Step.ScreenType -> buildScreenType()
            Step.Agreement -> buildAgreement()
            Step.Qr -> buildQr()
        }
    }

    private fun buildWelcome() {
        replaceContent(createScreen {
            addLogo(76)
            addTitle("欢迎使用")
            addBody("QMME", 15f)
            addSpacer(28)
            addPrimaryButton("开始使用") { showStep(Step.ScreenType) }
        })
    }

    private fun buildScreenType() {
        replaceContent(createScreen {
            addTitle("选择屏幕适配类型")
            addBody("这将影响列表的缩放效果", 14f)
            addSpacer(14)
            HRadioGroup(
                lparams = LayoutParams(widthMatchParent = true),
                init = {
                    orientation = AndroidLinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            ) {
                HMaterialRadioButton(
                    lparams = LayoutParams(widthMatchParent = true) {
                        topMargin = dp(4)
                    },
                    init = {
                        text = "圆形屏幕\n适用于圆形手表屏幕（未实装）"
                        TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                        isChecked = true
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                    }
                )
                HMaterialRadioButton(
                    lparams = LayoutParams(widthMatchParent = true) {
                        topMargin = dp(4)
                    },
                    init = {
                        text = "方形屏幕\n适用于方形或矩形屏幕"
                        TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                    }
                )
            }
            addSpacer(12)
            addPrimaryButton("继续") { showStep(Step.Agreement) }
            addSecondaryButton("返回") { showStep(Step.Welcome) }
        })
    }

    private fun buildAgreement() {
        replaceContent(createScreen {
            addLogo(60)
            addTitle("同意许可协议")
            addBody("继续使用即表示你同意 QQ Max 的用户许可协议与隐私说明。", 15f)
            addSpacer(18)
            val agreed = HMaterialCheckBox(
                lparams = LayoutParams(widthMatchParent = true),
                init = {
                    text = "我已阅读并同意"
                    TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
            )
            addSpacer(12)
            val next = addPrimaryButton("继续") { showStep(Step.Qr) }
            next.isEnabled = false
            agreed.setOnCheckedChangeListener { _, checked -> next.isEnabled = checked }
            addSecondaryButton("返回") { showStep(Step.ScreenType) }
        })
    }

    private fun buildQr() {
        replaceContent(createScreen {
            addTitle("扫码登录")
            addBody("请使用手机 QQ 扫描二维码", 14f)
            addSpacer(14)
            val image = HShapeableImageView(
                lparams = LayoutParams(width = dp(220), height = dp(220)) {
                    gravity = Gravity.CENTER_HORIZONTAL
                },
                init = {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    visibility = View.GONE
                }
            )
            qrImage = image
            val progress = HCircularProgressIndicator(
                lparams = LayoutParams(width = dp(44), height = dp(44)) {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(12)
                },
                init = {
                    visibility = View.VISIBLE
                }
            )
            qrProgress = progress
            qrStatus = addBody(statusText, 14f)
            addSpacer(14)
            qrRetry = addSecondaryButton("刷新二维码") { viewModel?.fetchQrCode() }
            addSecondaryButton("返回") {
                viewModel?.reset()
                showStep(Step.Agreement)
            }
        })
        renderQrState()
        viewModel?.fetchQrCode()
    }

    private fun renderQrState() {
        if (step != Step.Qr) return
        val image = qrImage ?: return
        val progress = qrProgress ?: return
        val status = qrStatus ?: return
        val retry = qrRetry ?: return
        image.setImageBitmap(qrBitmap)
        status.text = when (val state = loginUiState) {
            is AuthViewModel.LoginUiState.Error -> state.message
            AuthViewModel.LoginUiState.Expired -> "二维码已过期，请重新获取"
            else -> statusText
        }
        val ready = qrBitmap != null && loginUiState !is AuthViewModel.LoginUiState.Error
                && loginUiState !is AuthViewModel.LoginUiState.Expired
        image.visibility = if (ready) View.VISIBLE else View.GONE
        progress.visibility = if (ready || !busy) View.GONE else View.VISIBLE
        retry.isEnabled = !busy && (ready || loginUiState is AuthViewModel.LoginUiState.Error
                || loginUiState is AuthViewModel.LoginUiState.Expired)
    }

    private fun createScreen(
        contentBuilder: Hikage.Performer<AndroidLinearLayout.LayoutParams>.() -> Unit,
    ): Hikage = Hikagable(context) {
        HLinearLayout(
            lparams = LayoutParams(widthMatchParent = true),
            init = {
                orientation = AndroidLinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(14), dp(20), dp(14), dp(20))
            },
        ) {
            HMaterialCardView(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = dp(10)
                    bottomMargin = dp(10)
                },
                init = { isClickable = false },
            ) {
                HLinearLayout(
                    lparams = LayoutParams(matchParent = true),
                    init = {
                        orientation = AndroidLinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(18), dp(18), dp(18), dp(18))
                    },
                ) {
                    contentBuilder()
                }
            }
        }
    }

    private fun replaceContent(screen: Hikage) {
        contentRoot.removeAllViews()
        // hikage-extension 1.1.1 exposes the ViewGroup overload only to Java;
        // mount the already-built Hikage tree without introducing a wrapper View.
        contentRoot.addView(screen.root)
        contentRoot.requestLayout()
        scrollRoot.post { scrollRoot.scrollTo(0, 0) }
    }

    @HikagableAnnotation
    private fun Hikage.Performer<AndroidLinearLayout.LayoutParams>.addLogo(sizeDp: Int) {
        HShapeableImageView(
            lparams = LayoutParams(width = dp(sizeDp), height = dp(sizeDp)) {
                gravity = Gravity.CENTER_HORIZONTAL
            },
            init = {
                setImageResource(R.drawable.ic_launcher_foreground)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
        )
        addSpacer(14)
    }

    @HikagableAnnotation
    private fun Hikage.Performer<AndroidLinearLayout.LayoutParams>.addTitle(text: String) {
        HMaterialTextView(
            lparams = LayoutParams(widthMatchParent = true),
            init = {
                this.text = text
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                gravity = Gravity.CENTER
            }
        )
        addSpacer(8)
    }

    @HikagableAnnotation
    private fun Hikage.Performer<AndroidLinearLayout.LayoutParams>.addBody(
        text: String,
        @Suppress("UNUSED_PARAMETER")
        size: Float,
    ): MaterialTextViewView {
        return HMaterialTextView(
            lparams = LayoutParams(widthMatchParent = true),
            init = {
                this.text = text
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                gravity = Gravity.CENTER
            }
        )
    }

    @HikagableAnnotation
    private fun Hikage.Performer<AndroidLinearLayout.LayoutParams>.addSpacer(dp: Int) {
        HLinearLayout(
            lparams = LayoutParams(width = 1, height = dp(dp)),
        )
    }

    @HikagableAnnotation
    private fun Hikage.Performer<AndroidLinearLayout.LayoutParams>.addPrimaryButton(
        text: String,
        onClick: () -> Unit,
    ): MaterialButtonView = addButton(text, onClick, primary = true)

    @HikagableAnnotation
    private fun Hikage.Performer<AndroidLinearLayout.LayoutParams>.addSecondaryButton(
        text: String,
        onClick: () -> Unit,
    ): MaterialButtonView = addButton(text, onClick, primary = false)

    @HikagableAnnotation
    private fun Hikage.Performer<AndroidLinearLayout.LayoutParams>.addButton(
        text: String,
        onClick: () -> Unit,
        primary: Boolean,
    ): MaterialButtonView {
        val button = HMaterialButton(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = dp(4)
            },
            init = {
                this.text = text
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                isAllCaps = false
                if (!primary) setStrokeWidth(dp(1))
                setOnClickListener { onClick() }
            }
        )
        addSpacer(4)
        return button
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
