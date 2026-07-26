package rj.qmme.ui

import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.transition.TransitionManager
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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
import com.highcapable.hikage.widget.androidx.core.widget.NestedScrollView
import com.highcapable.hikage.widget.androidx.coordinatorlayout.widget.CoordinatorLayout
import com.highcapable.hikage.widget.androidx.recyclerview.widget.RecyclerView
import com.highcapable.hikage.widget.androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.highcapable.hikage.widget.com.google.android.material.appbar.AppBarLayout
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.bottomnavigation.BottomNavigationView
import com.highcapable.hikage.widget.com.google.android.material.card.MaterialCardView
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
import rj.qmme.ui.hikage.IconButton
import rj.qmme.ui.hikage.MediumCollapsingToolbar
import rj.qmme.ui.hikage.settingsGroup
import rj.qmme.viewmodel.ChatListViewModel
import rj.qmme.viewmodel.ContactsViewModel
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.hikage.annotation.Hikagable

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
    private lateinit var appBar: AppBarLayout
    private lateinit var collapsingToolbar: CollapsingToolbarLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var pagesHost: FrameLayout
    private lateinit var chatPage: LinearLayout
    private lateinit var contactsPage: LinearLayout
    private lateinit var myPage: NestedScrollView
    private lateinit var myAvatarView: ShapeableImageView
    private lateinit var myStatusView: MaterialTextView

    private lateinit var chatStatusCard: MaterialCardView
    private lateinit var chatStatus: MaterialTextView
    private lateinit var chatSwipeRefresh: SwipeRefreshLayout
    private lateinit var chatRecyclerView: RecyclerView

    private lateinit var contactsStatusCard: MaterialCardView
    private lateinit var contactsStatus: MaterialTextView
    private lateinit var contactsProgress: CircularProgressIndicator
    private lateinit var contactsRefresh: MaterialButton
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
                        renderUnreadBadge(snapshot.contacts.sumOf { it.unreadCnt })
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
        AvatarLoader.bindLogo(
            toolbar = toolbar,
            localPath = null,
            urls = AvatarSources.forSelf(account.uin),
            fallback = context.getDrawableCompat(R.drawable.ic_account_circle),
        )
        AvatarLoader.bind(
            imageView = myAvatarView,
            localPath = null,
            urls = AvatarSources.forSelf(account.uin),
            fallback = context.getDrawableCompat(R.drawable.ic_account_circle),
        )
        owner.launch {
            try {
                awaitCancellation()
            } finally {
                AvatarLoader.unbind(myAvatarView)
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
                AvatarLoader.bindLogo(
                    toolbar = toolbar,
                    localPath = selfAvatarPath,
                    urls = AvatarSources.forSelf(account.uin),
                    fallback = context.getDrawableCompat(R.drawable.ic_account_circle),
                )
                AvatarLoader.bind(
                    imageView = myAvatarView,
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
                CoordinatorLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
                ) {
                    appBar = AppBarLayout(
                        lparams = LayoutParams(widthMatchParent = true),
                        init = {
                            // Match the page canvas so the bar reads as the same
                            // expressive surface, not a separate band. Lift color
                            // churn is intentionally skipped for the same reason.
                            setBackgroundColor(
                                MaterialColors.getColor(
                                    this,
                                    com.google.android.material.R.attr.colorSurfaceContainer,
                                ),
                            )
                            isLiftOnScroll = false
                            elevation = 0f
                            EdgeToEdgeInsets.applyHorizontalInsets(this)
                        },
                    ) {
                        // M3 Expressive flexible medium app bar: large title +
                        // subtitle expanded, collapses into the pinned toolbar.
                        collapsingToolbar = MediumCollapsingToolbar(
                            lparams = LayoutParams(widthMatchParent = true),
                            init = {
                                title = "消息"
                                subtitle = onlineSubtitle()
                                setExpandedTitleTextAppearance(
                                    com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium_Emphasized,
                                )
                            },
                        ) {
                            toolbar = MaterialToolbar(
                                lparams = LayoutParams(
                                    widthMatchParent = true,
                                    height = context.actionBarSize(),
                                ),
                                init = {
                                    logo = drawableResource(R.drawable.ic_account_circle)
                                    contentDescription = "主导航"
                                },
                            )
                        }
                    }
                    pagesHost = FrameLayout(
                        lparams = LayoutParams(matchParent = true),
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
                    // Wire the Coordinator contract in code — Hikage's DSL owns
                    // the layout params, so flags/behaviors attach afterwards.
                    (collapsingToolbar.layoutParams as? AppBarLayout.LayoutParams)?.scrollFlags =
                        AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                        AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED or
                        AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
                    (toolbar.layoutParams as? CollapsingToolbarLayout.LayoutParams)?.collapseMode =
                        CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_PIN
                    (pagesHost.layoutParams as? CoordinatorLayout.LayoutParams)?.behavior =
                        AppBarLayout.ScrollingViewBehavior()
                    // Classic AppBarLayout + SwipeRefreshLayout conflict: a pull
                    // gesture must collapse-expand the bar first and only then
                    // become a refresh. Gate refresh on the fully-expanded state.
                    appBar.addOnOffsetChangedListener(
                        AppBarLayout.OnOffsetChangedListener { _, verticalOffset ->
                            val expanded = verticalOffset == 0
                            if (::chatSwipeRefresh.isInitialized) {
                                chatSwipeRefresh.isEnabled = expanded
                            }
                            if (::contactsSwipeRefresh.isInitialized) {
                                contactsSwipeRefresh.isEnabled = expanded
                            }
                        },
                    )
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

    @Hikagable
    private fun Hikage.Performer<FrameLayout.LayoutParams>.buildChatPage(): LinearLayout =
        LinearLayout(
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
                    marginStart = dp(12)
                    marginEnd = dp(12)
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
                            TextViewCompat.setTextAppearance(
                                this,
                                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                            )
                        },
                    )
                }
            }
            chatSwipeRefresh = SwipeRefreshLayout(
                lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
                init = {
                    applyM3Colors(this)
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

    @Hikagable
    private fun Hikage.Performer<FrameLayout.LayoutParams>.buildContactsPage(): LinearLayout =
        LinearLayout(
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
                    marginStart = dp(12)
                    marginEnd = dp(12)
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
                            marginEnd = dp(12)
                        },
                        init = {
                            isIndeterminate = true
                            visibility = View.GONE
                        },
                    )
                    contactsStatus = MaterialTextView(
                        lparams = LayoutParams(
                            width = 0,
                            height = ViewGroup.LayoutParams.WRAP_CONTENT
                        ) {
                            weight = 1f
                        },
                        init = {
                            text = "等待联系人服务…"
                            TextViewCompat.setTextAppearance(
                                this,
                                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                            )
                        },
                    )
                    // A Chip is filter/input semantics in M3; a manual refresh
                    // affordance is an icon button.
                    contactsRefresh = IconButton(
                        lparams = LayoutParams(width = dp(48), height = dp(48)),
                        init = {
                            icon = drawableResource(R.drawable.ic_refresh)
                            contentDescription = "刷新联系人"
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
                    applyM3Colors(this)
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

    /**
     * "Me" is a settings surface, so it uses the same official segmented list
     * items as the conversation and contact feeds instead of a card holding a
     * stack of full-width buttons. Destructive entries are error-colored rows,
     * which is the M3 Expressive pattern for irreversible actions.
     */
    @Hikagable
    private fun Hikage.Performer<FrameLayout.LayoutParams>.buildMyPage(): NestedScrollView =
        NestedScrollView(
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
                setPadding(dp(12), dp(8), dp(12), dp(24))
            },
        ) {
            buildProfileHeader()
            buildSectionLabel("账号")
            settingsGroup {
                row(
                    icon = context.getDrawableCompat(R.drawable.ic_account_circle),
                    title = "QQ 账号",
                    subtitle = account.uin.toString(),
                )
                row(
                    icon = context.getDrawableCompat(R.drawable.ic_power),
                    title = "登录存储",
                    subtitle = "已启用 · 登录状态保存在本机",
                )
            }
            buildSectionLabel("危险区域")
            settingsGroup {
                row(
                    icon = context.getDrawableCompat(R.drawable.ic_logout),
                    title = "退出登录",
                    subtitle = "清除本机登录状态并返回登录页",
                    destructive = true,
                    onClick = onLogout,
                )
                row(
                    icon = context.getDrawableCompat(R.drawable.ic_power),
                    title = "强制退出应用",
                    subtitle = "立即结束进程，不保存当前会话",
                    destructive = true,
                    onClick = onForceExit,
                )
            }
        }
    }

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildProfileHeader() {
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = dp(8)
                bottomMargin = dp(8)
            },
            init = {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            },
        ) {
            myAvatarView = ShapeableImageView(
                lparams = LayoutParams(width = dp(72), height = dp(72)),
                init = {
                    setImageResource(R.drawable.ic_account_circle)
                    AvatarLoader.makeCircular(this)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = "我的头像"
                },
            )
            MaterialTextView(
                lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                    topMargin = dp(12)
                },
                init = {
                    text = account.uin.toString()
                    // Emphasized typography is the M3 Expressive headline voice.
                    TextViewCompat.setTextAppearance(
                        this,
                        com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall_Emphasized,
                    )
                },
            )
            myStatusView = MaterialTextView(
                lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                    topMargin = dp(2)
                },
                init = {
                    text = onlineSubtitle()
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
        }
    }

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildSectionLabel(text: String) {
        MaterialTextView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = dp(16)
                bottomMargin = dp(6)
                marginStart = dp(16)
            },
            init = {
                this.text = text
                TextViewCompat.setTextAppearance(
                    this,
                    com.google.android.material.R.style.TextAppearance_Material3_LabelLarge_Emphasized,
                )
                textColor = MaterialColors.getColor(
                    this,
                    androidx.appcompat.R.attr.colorPrimary,
                )
            },
        )
    }

    /**
     * The official BottomNavigationView badge. It is anchored, sized and
     * colored by the M3 theme, so an unread count needs no custom drawing —
     * the data was already on every RecentContactInfo.
     */
    private fun renderUnreadBadge(total: Long) {
        val badge = navigation.getOrCreateBadge(PAGE_CHAT)
        if (total <= 0L) {
            badge.clearNumber()
            badge.isVisible = false
            return
        }
        badge.maxCharacterCount = 3
        badge.number = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        badge.isVisible = true
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
        val subtitle = onlineSubtitle()
        collapsingToolbar.subtitle = subtitle
        if (::myStatusView.isInitialized) myStatusView.text = subtitle
    }

    private fun showPage(page: View) {
        // Top-level destinations change with a fade-through, per M3 motion
        // guidance for bottom-navigation switches (not a lateral axis).
        TransitionManager.beginDelayedTransition(pagesHost, MaterialFadeThrough())
        chatPage.visibility = if (page === chatPage) View.VISIBLE else View.GONE
        contactsPage.visibility = if (page === contactsPage) View.VISIBLE else View.GONE
        myPage.visibility = if (page === myPage) View.VISIBLE else View.GONE
        collapsingToolbar.title = when (page) {
            chatPage -> "消息"
            contactsPage -> "联系人"
            else -> "我的"
        }
        // The flexible bar re-expands when landing on a new destination.
        appBar.setExpanded(true, true)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val PAGE_CHAT = 1
        private const val PAGE_CONTACTS = 2
        private const val PAGE_ME = 3

        /**
         * `?attr/actionBarSize` (the appcompat attr, which Material3Expressive
         * sets to 64dp; the framework attr would resolve to the platform 56dp).
         */
        private fun Context.actionBarSize(): Int {
            val value = TypedValue()
            theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, value, true)
            return TypedValue.complexToDimensionPixelSize(
                value.data,
                resources.displayMetrics,
            )
        }
    }
}
