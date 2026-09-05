package rj.qmme.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.highcapable.betterandroid.ui.extension.component.base.getDrawableCompat
import com.highcapable.betterandroid.ui.extension.component.launch
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.hikage.annotation.Hikagable
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.androidx.core.widget.NestedScrollView
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.button.MaterialButton
import com.highcapable.hikage.widget.com.google.android.material.progressindicator.CircularProgressIndicator
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rj.qmme.R
import rj.qmme.viewmodel.ChatDetailViewModel

/**
 * Streams an AI summary of the chat this screen was opened from.
 *
 * It reuses the chat's own [ChatDetailViewModel]: the request kicks off on
 * bind (the loaded transcript already lives there) and the stream is torn
 * down on dispose, so leaving the page always cancels the network work.
 */
class ChatSummaryHikagable(
    private val context: Context,
    private val chatTitle: String,
    private val onBack: () -> Unit,
) : HikageScreen {

    private lateinit var progress: CircularProgressIndicator
    private lateinit var statusText: MaterialTextView
    private lateinit var summaryText: MaterialTextView
    private lateinit var retryButton: MaterialButton

    private var bound = false
    private var boundViewModel: ChatDetailViewModel? = null
    private var cachedHikage: Hikage.Delegate<*>? = null

    override val hikage
        get() = cachedHikage ?: Hikagable {
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
                        title = "AI 摘要"
                        subtitle = chatTitle
                        navigationIcon = context.getDrawableCompat(R.drawable.ic_arrow_back)
                        setNavigationContentDescription("返回")
                        setNavigationOnClickListener { onBack() }
                        EdgeToEdgeInsets.applyHorizontalInsets(this)
                    },
                )
                NestedScrollView(
                    lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
                    init = {
                        isFillViewport = true
                        clipToPadding = false
                    },
                ) {
                    LinearLayout(
                        lparams = LayoutParams(widthMatchParent = true),
                        init = {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(20), dp(8), dp(20), dp(24))
                        },
                    ) {
                        progress = CircularProgressIndicator(
                            lparams = LayoutParams(
                                width = dp(28),
                                height = dp(28),
                            ) {
                                topMargin = dp(8)
                                gravity = android.view.Gravity.CENTER_HORIZONTAL
                            },
                            init = {
                                isIndeterminate = true
                                visibility = View.GONE
                            },
                        )
                        statusText = MaterialTextView(
                            lparams = LayoutParams(widthMatchParent = true) { topMargin = dp(8) },
                            init = {
                                text = "准备总结…"
                                TextViewCompat.setTextAppearance(
                                    this,
                                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                                )
                                textColor = MaterialColors.getColor(
                                    this,
                                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                                )
                            },
                        )
                        summaryText = MaterialTextView(
                            lparams = LayoutParams(widthMatchParent = true) { topMargin = dp(12) },
                            init = {
                                visibility = View.GONE
                                TextViewCompat.setTextAppearance(
                                    this,
                                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                                )
                                textColor = MaterialColors.getColor(
                                    this,
                                    com.google.android.material.R.attr.colorOnSurface,
                                )
                                setLineSpacing(dp(2).toFloat(), 1f)
                                setTextIsSelectable(true)
                            },
                        )
                        retryButton = MaterialButton(
                            lparams = LayoutParams(
                                width = ViewGroup.LayoutParams.WRAP_CONTENT,
                                height = ViewGroup.LayoutParams.WRAP_CONTENT,
                            ) {
                                topMargin = dp(16)
                                gravity = android.view.Gravity.CENTER_HORIZONTAL
                            },
                            init = {
                                text = "重新总结"
                                visibility = View.GONE
                                setOnClickListener {
                                    boundViewModel?.retryMessageSummary()
                                }
                            },
                        )
                    }
                }
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0),
                    init = { EdgeToEdgeInsets.applyBottomInsetSpacer(this) },
                )
            }
        }.also { cachedHikage = it }

    fun bind(owner: LifecycleOwner, viewModel: ChatDetailViewModel) {
        if (bound) return
        bound = true
        boundViewModel = viewModel
        viewModel.requestMessageSummary()
        owner.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.messageSummaryState.collectLatest { render(it) }
            }
        }
    }

    /** Called when the page leaves the navigation stack. */
    fun dispose() {
        boundViewModel?.dismissMessageSummary()
        boundViewModel = null
    }

    private fun render(state: ChatDetailViewModel.MessageSummaryState) {
        if (::progress.isInitialized.not()) return
        when (state) {
            ChatDetailViewModel.MessageSummaryState.Idle -> Unit
            is ChatDetailViewModel.MessageSummaryState.Loading -> {
                progress.visibility = View.VISIBLE
                statusText.text = "正在总结 ${state.selectedCount} 条消息…"
                statusText.visibility = View.VISIBLE
                summaryText.text = state.text
                summaryText.visibility = if (state.text.isBlank()) View.GONE else View.VISIBLE
                retryButton.visibility = View.GONE
            }
            is ChatDetailViewModel.MessageSummaryState.Success -> {
                progress.visibility = View.GONE
                statusText.visibility = View.GONE
                summaryText.text = state.text
                summaryText.visibility = View.VISIBLE
                retryButton.visibility = View.GONE
            }
            is ChatDetailViewModel.MessageSummaryState.Error -> {
                progress.visibility = View.GONE
                statusText.text = state.message
                statusText.visibility = View.VISIBLE
                summaryText.text = ""
                summaryText.visibility = View.GONE
                retryButton.visibility = if (state.retryable) View.VISIBLE else View.GONE
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
