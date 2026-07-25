package rj.qmme.ui

import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.highcapable.betterandroid.ui.extension.component.base.getDrawableCompat
import com.highcapable.betterandroid.ui.extension.component.launch
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.FrameLayout
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.android.widget.ScrollView
import com.highcapable.hikage.widget.androidx.recyclerview.widget.RecyclerView
import com.highcapable.hikage.widget.androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.highcapable.hikage.widget.com.google.android.material.bottomnavigation.BottomNavigationView
import com.highcapable.hikage.widget.com.google.android.material.button.MaterialButton
import com.highcapable.hikage.widget.com.google.android.material.card.MaterialCardView
import com.highcapable.hikage.widget.com.google.android.material.chip.Chip
import com.highcapable.hikage.widget.com.google.android.material.divider.MaterialDivider
import com.highcapable.hikage.widget.com.google.android.material.imageview.ShapeableImageView
import com.highcapable.hikage.widget.com.google.android.material.progressindicator.CircularProgressIndicator
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView
import com.tencent.qphone.base.remote.SimpleAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rj.qmme.QmmeApp
import rj.qmme.R
import rj.qmme.data.OnlineStatus
import rj.qmme.kernel.KernelBridge
import rj.qmme.runtime.RuntimeCoordinator
import rj.qmme.viewmodel.ChatListViewModel
import rj.qmme.viewmodel.ContactsViewModel
import com.highcapable.hikage.annotation.Hikagable as HikagableAnnotation

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
    private val onOpenChat: (com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo) -> Unit,
    private val onOpenContactChat: (ContactsViewModel.UiBuddy) -> Unit,
) : HikageScreen {
    private lateinit var avatarView: ShapeableImageView
    private lateinit var toolbarTitle: MaterialTextView
    private lateinit var toolbarSubtitle: MaterialTextView
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

        chatRecyclerView.adapter = ConversationAdapter(chatViewModel, onOpenChat)
        contactsRecyclerView.adapter = ContactsAdapter(onOpenContactChat)

        owner.launch {
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
                launch {
                    val onlineObserver = ::renderOnlineStatus
                    OnlineStatus.addObserver(onlineObserver)
                    renderOnlineStatus()
                    try {
                        awaitCancellation()
                    } finally {
                        OnlineStatus.removeObserver(onlineObserver)
                    }
                }
            }
        }

        // Start the visible avatar immediately with the remote fallback; the
        // self-profile path below replaces it when QQ exposes a local copy.
        avatarView.scaleType = ImageView.ScaleType.CENTER_CROP
        AvatarLoader.bind(
            imageView = avatarView,
            localPath = null,
            urls = AvatarSources.forSelf(account.uin),
            fallback = context.getDrawableCompat(R.drawable.ic_account_circle),
        )
        owner.launch {
            try {
                awaitCancellation()
            } finally {
                AvatarLoader.unbind(avatarView)
            }
        }

        // Keep service bootstrap off the main thread.  This is the same
        // persisted-account binding path used by the kernel/chat pipeline.
        owner.launch(Dispatchers.IO) {
            val runtime = RuntimeCoordinator.currentRuntime() ?: QmmeApp.ensureRuntime()
            val kernelReady = KernelBridge.getBuddyService() != null &&
                    KernelBridge.getRecentContactService() != null
            if (!kernelReady) {
                val bindResult = KernelBridge.bindLoggedInAccount(account.uin, account)
                Log.i("QMME", "main: bind persisted account result=$bindResult")
            }
            val readyRuntime = RuntimeCoordinator.currentRuntime() ?: runtime
            RuntimeCoordinator.observeLegacyMirror(
                QmmeApp.sAppRuntime,
                source = "MainHikagable.persistedAccountBootstrap",
            )
            val selfAvatarPath = KernelBridge.getSelfProfileService()
                ?.getCurrentAccountAvatarPath(account.uin)
                .orEmpty()
            withContext(Dispatchers.Main.immediate) {
                avatarView.scaleType = ImageView.ScaleType.CENTER_CROP
                AvatarLoader.bind(
                    imageView = avatarView,
                    localPath = selfAvatarPath,
                    urls = AvatarSources.forSelf(account.uin),
                    fallback = context.getDrawableCompat(R.drawable.ic_account_circle),
                )
            }
            KernelBridge.getKernelService()?.getProfileService()?.let { profileService ->
                OnlineStatus.start(profileService, account.uin)
            }
            withContext(Dispatchers.Main.immediate) { renderOnlineStatus() }
            chatViewModel.loadContacts(readyRuntime)
            contactsViewModel.loadBuddies(readyRuntime)
        }
    }

    override val hikage
        get() = cachedHikage ?: Hikagable {
            LinearLayout(
                lparams = LayoutParams(matchParent = true),
                init = {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(
                        MaterialColors.getColor(
                            this,
                            com.google.android.material.R.attr.colorSurfaceContainer,
                        ),
                    )
                },
            ) {
                // This is part of the Hikage tree, not a post-build View patch.
                // It fills the transparent status-bar/cutout region with the same
                // container surface as the app bar.
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0),
                    init = {
                        setBackgroundColor(
                            MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorSurfaceContainer,
                            ),
                        )
                        EdgeToEdgeInsets.applyTopInsetSpacer(this)
                    },
                )
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(56)
                        // Keep the app bar in the same M3 surface container as
                        // the active page rather than letting it read as a band.
                        setBackgroundColor(
                            MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorSurfaceContainer,
                            ),
                        )
                        contentDescription = "主导航"
                        EdgeToEdgeInsets.applyHorizontalInsets(this)
                    },
                ) {
                    avatarView = ShapeableImageView(
                        lparams = LayoutParams(width = dp(40), height = dp(40)) {
                            leftMargin = dp(16)
                        },
                        init = {
                            setImageResource(R.drawable.ic_account_circle)
                            AvatarLoader.makeCircular(this)
                            scaleType = ImageView.ScaleType.CENTER_INSIDE
                            contentDescription = "我的头像"
                        },
                    )
                    LinearLayout(
                        lparams = LayoutParams(
                            width = ViewGroup.LayoutParams.WRAP_CONTENT,
                            height = ViewGroup.LayoutParams.WRAP_CONTENT,
                        ) { leftMargin = dp(12) },
                        init = { orientation = LinearLayout.VERTICAL },
                    ) {
                        toolbarTitle = MaterialTextView(
                            lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT),
                            init = {
                                text = "消息"
                                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                            },
                        )
                        toolbarSubtitle = MaterialTextView(
                            lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT),
                            init = {
                                text = onlineSubtitle()
                                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                            },
                        )
                    }
                }

                FrameLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
                    init = {
                        clipChildren = false
                        clipToPadding = false
                        EdgeToEdgeInsets.applyHorizontalInsets(this)
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
                navigation = BottomNavigationView(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        EdgeToEdgeInsets.applyHorizontalInsets(this)
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
                                        RuntimeCoordinator.currentRuntime(),
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
                // Keep the navigation component at its native Material height;
                // the companion Hikage spacer owns only the gesture-bar region.
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0),
                    init = {
                        setBackgroundColor(
                            MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorSurfaceContainer,
                            ),
                        )
                        EdgeToEdgeInsets.applyBottomInsetSpacer(this)
                    },
                )
            }
        }.also { cachedHikage = it }

    @HikagableAnnotation
    private fun Hikage.Performer<FrameLayout.LayoutParams>.buildChatPage(): LinearLayout = LinearLayout(
        lparams = LayoutParams(matchParent = true),
        init = {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
            setBackgroundColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurfaceContainer,
                ),
            )
        },
    ) {
        chatStatusCard = MaterialCardView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = dp(12)
                leftMargin = dp(12)
                rightMargin = dp(12)
                bottomMargin = dp(4)
            },
            init = { isClickable = false },
        ) {
            LinearLayout(
                lparams = LayoutParams(matchParent = true),
                init = {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                },
            ) {
                chatStatus = MaterialTextView(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        text = "正在连接 QQ 服务…"
                        TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    },
                )
            }
        }
        chatSwipeRefresh = SwipeRefreshLayout(
            lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
            init = {
                setOnRefreshListener { boundChatViewModel?.refreshContacts() }
            },
        ) {
            chatRecyclerView = RecyclerView(
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
    private fun Hikage.Performer<FrameLayout.LayoutParams>.buildContactsPage(): LinearLayout = LinearLayout(
        lparams = LayoutParams(matchParent = true),
        init = {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
            setBackgroundColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurfaceContainer,
                ),
            )
        },
    ) {
        contactsStatusCard = MaterialCardView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = dp(12)
                leftMargin = dp(12)
                rightMargin = dp(12)
                bottomMargin = dp(4)
            },
            init = { isClickable = false },
        ) {
            LinearLayout(
                lparams = LayoutParams(matchParent = true),
                init = {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(8), dp(8), dp(8))
                },
            ) {
                contactsProgress = CircularProgressIndicator(
                    lparams = LayoutParams(width = dp(22), height = dp(22)) {
                        rightMargin = dp(12)
                    },
                    init = {
                        isIndeterminate = true
                        visibility = View.GONE
                    },
                )
                contactsStatus = MaterialTextView(
                    lparams = LayoutParams(width = 0, height = ViewGroup.LayoutParams.WRAP_CONTENT) {
                        weight = 1f
                    },
                    init = {
                        text = "等待联系人服务…"
                        TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    },
                )
                contactsRefresh = Chip(
                    lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT),
                    init = {
                        text = "刷新"
                        isCheckable = false
                        setChipIconResource(R.drawable.ic_refresh)
                        isChipIconVisible = true
                        TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                        setOnClickListener {
                            boundContactsViewModel?.refresh(RuntimeCoordinator.currentRuntime())
                        }
                    },
                )
            }
        }
        contactsSwipeRefresh = SwipeRefreshLayout(
            lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
            init = {
                setOnRefreshListener {
                    boundContactsViewModel?.refresh(RuntimeCoordinator.currentRuntime())
                }
            },
        ) {
            contactsRecyclerView = RecyclerView(
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
    private fun Hikage.Performer<FrameLayout.LayoutParams>.buildMyPage(): ScrollView = ScrollView(
        lparams = LayoutParams(matchParent = true),
        init = {
            isFillViewport = true
            clipToPadding = false
        },
    ) {
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true),
            init = {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(24))
            },
        ) {
            MaterialCardView(
                lparams = LayoutParams(widthMatchParent = true),
                init = { isClickable = false },
            ) {
                LinearLayout(
                    lparams = LayoutParams(matchParent = true),
                    init = {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(20), dp(20), dp(20), dp(16))
                    },
                ) {
                    MaterialTextView(
                        lparams = LayoutParams(widthMatchParent = true),
                        init = {
                            text = "QQ 账号"
                            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                        },
                    )
                    MaterialTextView(
                        lparams = LayoutParams(widthMatchParent = true) { topMargin = dp(4) },
                        init = {
                            text = account.uin.toString()
                            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                        },
                    )
                    Chip(
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
                    MaterialDivider(
                        lparams = LayoutParams(widthMatchParent = true) { topMargin = dp(12) },
                    )
                    MaterialTextView(
                        lparams = LayoutParams(widthMatchParent = true) { topMargin = dp(12) },
                        init = {
                            text = "登录状态会保存在本机，QQ 服务将在后台继续工作。"
                            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                        },
                    )
                    MaterialButton(
                        lparams = LayoutParams(widthMatchParent = true) { topMargin = dp(16) },
                        init = {
                            text = "退出登录"
                            isAllCaps = false
                            icon = drawableResource(R.drawable.ic_logout)
                            setOnClickListener { onLogout() }
                        },
                    )
                    MaterialButton(
                        lparams = LayoutParams(widthMatchParent = true) { topMargin = dp(8) },
                        init = {
                            text = "强制退出应用"
                            isAllCaps = false
                            icon = drawableResource(R.drawable.ic_power)
                            strokeWidth = dp(1)
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

    private fun onlineSubtitle(): String =
        OnlineStatus.describe() ?: "正在同步在线状态"

    private fun renderOnlineStatus() {
        toolbarSubtitle.text = onlineSubtitle()
    }

    private fun showPage(page: View) {
        chatPage.visibility = if (page === chatPage) View.VISIBLE else View.GONE
        contactsPage.visibility = if (page === contactsPage) View.VISIBLE else View.GONE
        myPage.visibility = if (page === myPage) View.VISIBLE else View.GONE
        toolbarTitle.text = when (page) {
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
