package rj.qmme.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.highcapable.hikage.annotation.Hikagable as HikagableAnnotation
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.FrameLayout as HFrameLayout
import com.highcapable.hikage.widget.android.widget.LinearLayout as HLinearLayout
import com.highcapable.hikage.widget.android.widget.ScrollView as HScrollView
import com.highcapable.hikage.widget.androidx.recyclerview.widget.RecyclerView as HRecyclerView
import com.highcapable.hikage.widget.androidx.swiperefreshlayout.widget.SwipeRefreshLayout as HSwipeRefreshLayout
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar as HMaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.bottomnavigation.BottomNavigationView as HBottomNavigationView
import com.highcapable.hikage.widget.com.google.android.material.card.MaterialCardView as HMaterialCardView
import com.highcapable.hikage.widget.com.google.android.material.chip.Chip as HChip
import com.highcapable.hikage.widget.com.google.android.material.divider.MaterialDivider as HMaterialDivider
import com.highcapable.hikage.widget.com.google.android.material.button.MaterialButton as HMaterialButton
import com.highcapable.hikage.widget.com.google.android.material.progressindicator.CircularProgressIndicator as HCircularProgressIndicator
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView as HMaterialTextView
import com.tencent.qphone.base.remote.SimpleAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rj.qmme.QmmeApp
import rj.qmme.R
import rj.qmme.kernel.KernelBridge
import rj.qmme.viewmodel.ChatListViewModel
import rj.qmme.viewmodel.ContactsViewModel

/**
 * The whole signed-in surface is one native Hikage tree.  It deliberately
 * uses Material 3 components and theme typography instead of Compose or
 * hand-tuned colors/radii, so dynamic colors remain the single source of truth.
 */
class MainHikagable(
    private val context: Context,
    private val account: SimpleAccount,
    private val onLogout: () -> Unit,
    private val onForceExit: () -> Unit,
) : HikageScreen {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var chatPage: LinearLayout
    private lateinit var contactsPage: LinearLayout
    private lateinit var myPage: ScrollView

    private lateinit var chatStatusCard: MaterialCardView
    private lateinit var chatStatus: MaterialTextView
    private lateinit var chatSwipeRefresh: SwipeRefreshLayout
    private lateinit var chatRecyclerView: RecyclerView

    private lateinit var contactsStatusCard: MaterialCardView
    private lateinit var contactsStatus: MaterialTextView
    private lateinit var contactsProgress: CircularProgressIndicator
    private lateinit var contactsRefresh: Chip
    private lateinit var contactsSwipeRefresh: SwipeRefreshLayout
    private lateinit var contactsRecyclerView: RecyclerView

    private lateinit var navigation: BottomNavigationView

    private var boundChatViewModel: ChatListViewModel? = null
    private var boundContactsViewModel: ContactsViewModel? = null
    private var bound = false
    private var cachedHikage: Hikage.Delegate<*>? = null

    fun bind(
        owner: LifecycleOwner,
        chatViewModel: ChatListViewModel,
        contactsViewModel: ContactsViewModel,
    ) {
        if (bound) return
        bound = true
        boundChatViewModel = chatViewModel
        boundContactsViewModel = contactsViewModel

        chatRecyclerView.adapter = ConversationAdapter(chatViewModel) { contact ->
            // ChatDetail migration is next; keep the native row action explicit for now.
            android.util.Log.d("QMME", "open conversation peerUid=${contact.peerUid}")
        }
        contactsRecyclerView.adapter = ContactsAdapter { buddy ->
            android.util.Log.d(
                "QMME",
                "open contact uid=${buddy.uid} uin=${buddy.uin} name=${buddy.nick}",
            )
        }

        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    chatViewModel.contacts.collectLatest { snapshot ->
                        (chatRecyclerView.adapter as? ConversationAdapter)
                            ?.submitList(snapshot.contacts)
                    }
                }
                launch {
                    chatViewModel.statusText.collectLatest { chatStatus.text = it }
                }
                launch {
                    chatViewModel.isStatusVisible.collectLatest { visible ->
                        chatStatusCard.visibility = if (visible) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    chatViewModel.isRefreshing.collectLatest {
                        chatSwipeRefresh.isRefreshing = it
                    }
                }
                launch {
                    contactsViewModel.categories.collectLatest { categories ->
                        (contactsRecyclerView.adapter as? ContactsAdapter)
                            ?.submitCategories(categories)
                        renderContactsState(categories)
                    }
                }
                launch {
                    contactsViewModel.statusText.collectLatest { status ->
                        if (contactsViewModel.categories.value.isEmpty()) {
                            contactsStatus.text = status
                        }
                    }
                }
                launch {
                    contactsViewModel.loading.collectLatest { loading ->
                        contactsProgress.visibility = if (loading) View.VISIBLE else View.GONE
                        contactsRefresh.isEnabled = !loading
                        if (contactsViewModel.categories.value.isEmpty()) {
                            contactsStatusCard.visibility = View.VISIBLE
                        }
                    }
                }
                launch {
                    contactsViewModel.isRefreshing.collectLatest { refreshing ->
                        contactsSwipeRefresh.isRefreshing = refreshing
                    }
                }
            }
        }

        // Keep service bootstrap off the main thread.  This is the same
        // persisted-account binding path used by the kernel/chat pipeline.
        owner.lifecycleScope.launch(Dispatchers.IO) {
            val runtime = QmmeApp.sAppRuntime ?: QmmeApp.ensureRuntime()
            val kernelReady = KernelBridge.getBuddyService() != null &&
                    KernelBridge.getRecentContactService() != null
            if (!kernelReady) {
                val bindResult = KernelBridge.bindLoggedInAccount(account.uin, account)
                android.util.Log.i("QMME", "main: bind persisted account result=$bindResult")
            }
            val readyRuntime = QmmeApp.sAppRuntime ?: runtime
            chatViewModel.loadContacts(readyRuntime)
            contactsViewModel.loadBuddies(readyRuntime)
        }
    }

    override val hikage
        get() = cachedHikage ?: Hikagable {
            HLinearLayout(
                lparams = LayoutParams(matchParent = true),
                init = { orientation = LinearLayout.VERTICAL },
            ) {
                toolbar = HMaterialToolbar(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        title = "消息"
                        subtitle = "QQ · ${account.uin}"
                        setTitleTextAppearance(context, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                        setSubtitleTextAppearance(context, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                        contentDescription = "主导航"
                    },
                )
                HMaterialDivider(lparams = LayoutParams(widthMatchParent = true))

                HFrameLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
                    init = {
                        clipChildren = false
                        clipToPadding = false
                    },
                ) {
                    chatPage = buildChatPage()
                    contactsPage = buildContactsPage()
                    myPage = buildMyPage()
                    chatPage.visibility = View.VISIBLE
                    contactsPage.visibility = View.GONE
                    myPage.visibility = View.GONE
                }

                // Use the official M3 Expressive BottomNavigationView directly.
                // Theme.Material3Expressive supplies its container tone, elevation
                // and active indicator; no extra CardView or custom surface.
                navigation = HBottomNavigationView(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
                        menu.add(0, PAGE_CHAT, 0, "消息").setIcon(R.drawable.ic_chat)
                        menu.add(0, PAGE_CONTACTS, 1, "联系人").setIcon(R.drawable.ic_contacts)
                        menu.add(0, PAGE_ME, 2, "我的").setIcon(R.drawable.ic_account_circle)
                        setOnItemSelectedListener { item ->
                            when (item.itemId) {
                                PAGE_CHAT -> showPage(chatPage)
                                PAGE_CONTACTS -> {
                                    showPage(contactsPage)
                                    boundContactsViewModel?.loadBuddies(
                                        QmmeApp.sAppRuntime,
                                        forceRefresh = false,
                                    )
                                }

                                PAGE_ME -> showPage(myPage)
                                else -> return@setOnItemSelectedListener false
                            }
                            true
                        }
                        selectedItemId = PAGE_CHAT
                    },
                )
            }
        }.also { cachedHikage = it }

    @HikagableAnnotation
    private fun Hikage.Performer<FrameLayout.LayoutParams>.buildChatPage(): LinearLayout = HLinearLayout(
        lparams = LayoutParams(matchParent = true),
        init = {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
        },
    ) {
        chatStatusCard = HMaterialCardView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = dp(12)
                leftMargin = dp(12)
                rightMargin = dp(12)
                bottomMargin = dp(4)
            },
            init = { isClickable = false },
        ) {
            HLinearLayout(
                lparams = LayoutParams(matchParent = true),
                init = {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                },
            ) {
                chatStatus = HMaterialTextView(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        text = "正在连接 QQ 服务…"
                        TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    },
                )
            }
        }
        chatSwipeRefresh = HSwipeRefreshLayout(
            lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
            init = {
                setOnRefreshListener { boundChatViewModel?.refreshContacts() }
            },
        ) {
            chatRecyclerView = HRecyclerView(
                lparams = LayoutParams(matchParent = true),
                init = {
                    layoutManager = LinearLayoutManager(context)
                    overScrollMode = View.OVER_SCROLL_NEVER
                    clipToPadding = false
                    setPadding(dp(4), dp(4), dp(4), dp(10))
                },
            )
        }
    }

    @HikagableAnnotation
    private fun Hikage.Performer<FrameLayout.LayoutParams>.buildContactsPage(): LinearLayout = HLinearLayout(
        lparams = LayoutParams(matchParent = true),
        init = {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
        },
    ) {
        contactsStatusCard = HMaterialCardView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = dp(12)
                leftMargin = dp(12)
                rightMargin = dp(12)
                bottomMargin = dp(4)
            },
            init = { isClickable = false },
        ) {
            HLinearLayout(
                lparams = LayoutParams(matchParent = true),
                init = {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(8), dp(8), dp(8))
                },
            ) {
                contactsProgress = HCircularProgressIndicator(
                    lparams = LayoutParams(width = dp(22), height = dp(22)) {
                        rightMargin = dp(12)
                    },
                    init = {
                        isIndeterminate = true
                        visibility = View.GONE
                    },
                )
                contactsStatus = HMaterialTextView(
                    lparams = LayoutParams(width = 0, height = ViewGroup.LayoutParams.WRAP_CONTENT) {
                        weight = 1f
                    },
                    init = {
                        text = "等待联系人服务…"
                        TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    },
                )
                contactsRefresh = HChip(
                    lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT),
                    init = {
                        text = "刷新"
                        isCheckable = false
                        setChipIconResource(R.drawable.ic_refresh)
                        isChipIconVisible = true
                        TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                        setOnClickListener {
                            boundContactsViewModel?.refresh(QmmeApp.sAppRuntime)
                        }
                    },
                )
            }
        }
        contactsSwipeRefresh = HSwipeRefreshLayout(
            lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
            init = {
                setOnRefreshListener {
                    boundContactsViewModel?.refresh(QmmeApp.sAppRuntime)
                }
            },
        ) {
            contactsRecyclerView = HRecyclerView(
                lparams = LayoutParams(matchParent = true),
                init = {
                    layoutManager = LinearLayoutManager(context)
                    overScrollMode = View.OVER_SCROLL_NEVER
                    clipToPadding = false
                    setPadding(dp(4), dp(4), dp(4), dp(10))
                },
            )
        }
    }

    @HikagableAnnotation
    private fun Hikage.Performer<FrameLayout.LayoutParams>.buildMyPage(): ScrollView = HScrollView(
        lparams = LayoutParams(matchParent = true),
        init = {
            isFillViewport = true
            clipToPadding = false
        },
    ) {
        HLinearLayout(
            lparams = LayoutParams(widthMatchParent = true),
            init = {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(24))
            },
        ) {
            HMaterialCardView(
                lparams = LayoutParams(widthMatchParent = true),
                init = { isClickable = false },
            ) {
                HLinearLayout(
                    lparams = LayoutParams(matchParent = true),
                    init = {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(20), dp(20), dp(20), dp(16))
                    },
                ) {
                    HMaterialTextView(
                        lparams = LayoutParams(widthMatchParent = true),
                        init = {
                            text = "QQ 账号"
                            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                        },
                    )
                    HMaterialTextView(
                        lparams = LayoutParams(widthMatchParent = true) { topMargin = dp(4) },
                        init = {
                            text = account.uin.toString()
                            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                        },
                    )
                    HChip(
                        lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                            topMargin = dp(12)
                        },
                        init = {
                            text = "登录存储已启用"
                            isCheckable = false
                            isClickable = false
                            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                        },
                    )
                    HMaterialDivider(
                        lparams = LayoutParams(widthMatchParent = true) { topMargin = dp(12) },
                    )
                    HMaterialTextView(
                        lparams = LayoutParams(widthMatchParent = true) { topMargin = dp(12) },
                        init = {
                            text = "登录状态会保存在本机，QQ 服务将在后台继续工作。"
                            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                        },
                    )
                    HMaterialButton(
                        lparams = LayoutParams(widthMatchParent = true) { topMargin = dp(16) },
                        init = {
                            text = "退出登录"
                            isAllCaps = false
                            icon = context.getDrawable(R.drawable.ic_logout)
                            setOnClickListener { onLogout() }
                        },
                    )
                    HMaterialButton(
                        lparams = LayoutParams(widthMatchParent = true) { topMargin = dp(8) },
                        init = {
                            text = "强制退出应用"
                            isAllCaps = false
                            icon = context.getDrawable(R.drawable.ic_power)
                            setStrokeWidth(dp(1))
                            setOnClickListener { onForceExit() }
                        },
                    )
                }
            }
        }
    }

    private fun renderContactsState(categories: List<ContactsViewModel.UiCategory>) {
        if (categories.isNotEmpty()) {
            contactsStatus.text = "${categories.sumOf { it.buddies.size }} 位联系人"
            contactsStatusCard.visibility = View.VISIBLE
        }
    }

    private fun showPage(page: View) {
        chatPage.visibility = if (page === chatPage) View.VISIBLE else View.GONE
        contactsPage.visibility = if (page === contactsPage) View.VISIBLE else View.GONE
        myPage.visibility = if (page === myPage) View.VISIBLE else View.GONE
        toolbar.title = when (page) {
            chatPage -> "消息"
            contactsPage -> "联系人"
            else -> "我的"
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val PAGE_CHAT = 1
        private const val PAGE_CONTACTS = 2
        private const val PAGE_ME = 3
    }
}
