package rj.qmme.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import com.highcapable.hikage.annotation.Hikagable
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.androidx.recyclerview.widget.RecyclerView
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.textfield.TextInputEditText
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView
import androidx.core.widget.TextViewCompat
import rj.qmme.R
import rj.qmme.viewmodel.ChatDetailViewModel
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Calendar
import java.util.Date

/** Local search over already-loaded chat messages. */
class ChatSearchHikagable(
    private val context: Context,
    private val onBack: () -> Unit,
    private val onResultClick: (messageId: Long) -> Unit,
    private val searchFn: (String) -> List<ChatDetailViewModel.UiMessage>,
) : HikageScreen {
    private lateinit var searchInput: TextInputEditText
    private lateinit var recyclerView: RecyclerView
    private val adapter = SearchResultAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
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
                        title = "搜索"
                        navigationIcon = drawableResource(R.drawable.ic_arrow_back)
                        setNavigationContentDescription("返回")
                        setNavigationOnClickListener { onBack() }
                        EdgeToEdgeInsets.applyHorizontalInsets(this)
                    },
                )
                searchInput = TextInputEditText(
                    lparams = LayoutParams(widthMatchParent = true) {
                        marginStart = dp(16)
                        marginEnd = dp(16)
                        topMargin = dp(4)
                        bottomMargin = dp(4)
                    },
                    init = {
                        hint = "搜索聊天记录"
                        setSingleLine()
                        TextViewCompat.setTextAppearance(
                            this,
                            com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
                        )
                        addTextChangedListener(searchWatcher)
                        EdgeToEdgeInsets.applyHorizontalInsets(this)
                    },
                )
                recyclerView = RecyclerView(
                    lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
                    init = {
                        layoutManager = LinearLayoutManager(context)
                        adapter = this@ChatSearchHikagable.adapter
                        clipToPadding = false
                        setPadding(dp(8), dp(4), dp(8), dp(8))
                    },
                )
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0),
                    init = { EdgeToEdgeInsets.applyBottomInsetSpacer(this) },
                )
            }
        }.also { cachedHikage = it }

    fun dispose() {
        pendingSearch?.let { handler.removeCallbacks(it) }
        if (::searchInput.isInitialized) {
            searchInput.removeTextChangedListener(searchWatcher)
        }
    }

    private val searchWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            pendingSearch?.let { handler.removeCallbacks(it) }
            val query = s?.toString().orEmpty()
            val task = Runnable {
                adapter.submit(searchFn(query))
            }
            pendingSearch = task
            handler.postDelayed(task, SEARCH_DEBOUNCE_MS)
        }
    }

    private inner class SearchResultAdapter :
        RecyclerView.Adapter<SearchResultAdapter.Holder>() {
        private var results: List<ChatDetailViewModel.UiMessage> = emptyList()

        fun submit(list: List<ChatDetailViewModel.UiMessage>) {
            results = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            lateinit var sender: MaterialTextView
            lateinit var preview: MaterialTextView
            lateinit var time: MaterialTextView
            val hikage = Hikagable(parent.context) {
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        isClickable = true
                        isFocusable = true
                    },
                ) {
                    LinearLayout(
                        lparams = LayoutParams(widthMatchParent = true),
                        init = {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        },
                    ) {
                        sender = MaterialTextView(
                            lparams = LayoutParams(
                                width = 0,
                                height = ViewGroup.LayoutParams.WRAP_CONTENT,
                            ) { weight = 1f },
                            init = {
                                TextViewCompat.setTextAppearance(
                                    this,
                                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
                                )
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                            },
                        )
                        time = MaterialTextView(
                            lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT),
                            init = {
                                TextViewCompat.setTextAppearance(
                                    this,
                                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
                                )
                                setTextColor(
                                    MaterialColors.getColor(
                                        this,
                                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                                    ),
                                )
                            },
                        )
                    }
                    preview = MaterialTextView(
                        lparams = LayoutParams(widthMatchParent = true) {
                            topMargin = dp(2)
                        },
                        init = {
                            TextViewCompat.setTextAppearance(
                                this,
                                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                            )
                            setTextColor(
                                MaterialColors.getColor(
                                    this,
                                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                                ),
                            )
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                        },
                    )
                }
            }
            val root = hikage.root as LinearLayout
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            return Holder(root, sender, preview, time)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val message = results[position]
            holder.sender.text = message.senderName.ifBlank { "未知" }
            holder.preview.text = message.text.ifBlank { "[非文本消息]" }
            holder.time.text = formatMessageTime(message.timestampSeconds)
            holder.itemView.setOnClickListener { onResultClick(message.messageId) }
        }

        override fun getItemCount(): Int = results.size

        inner class Holder(
            itemView: View,
            val sender: MaterialTextView,
            val preview: MaterialTextView,
            val time: MaterialTextView,
        ) : RecyclerView.ViewHolder(itemView)
    }

    private fun formatMessageTime(timestampSeconds: Long): String {
        if (timestampSeconds <= 0L) return ""
        val millis = timestampSeconds * 1000L
        val clock = DateFormat.getTimeFormat(context).format(Date(millis))

        val then = Calendar.getInstance().apply { timeInMillis = millis }
        val now = Calendar.getInstance()

        fun Calendar.startOfDay(): Long = (clone() as Calendar).run {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            timeInMillis
        }
        val dayDiff = ((now.startOfDay() - then.startOfDay()) / MILLIS_PER_DAY).toInt()

        return when {
            dayDiff <= 0 -> clock
            dayDiff == 1 -> "昨天 $clock"
            dayDiff < 7 -> "${WEEKDAYS[then.get(Calendar.DAY_OF_WEEK) - 1]} $clock"
            then.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
                "${then.get(Calendar.MONTH) + 1}月${then.get(Calendar.DAY_OF_MONTH)}日 $clock"
            else ->
                "${then.get(Calendar.YEAR)}/${then.get(Calendar.MONTH) + 1}/" +
                    "${then.get(Calendar.DAY_OF_MONTH)} $clock"
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val MILLIS_PER_DAY = 86_400_000L
        val WEEKDAYS = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
    }
}
