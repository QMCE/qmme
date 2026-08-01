package rj.qmme.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.google.android.material.transition.MaterialContainerTransform
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.repeatOnLifecycle
import com.highcapable.betterandroid.ui.extension.component.launch
import com.highcapable.betterandroid.ui.extension.view.toast
import com.tencent.qphone.base.remote.SimpleAccount
import mqq.app.Constants
import rj.qmme.QmmeApp
import rj.qmme.data.AppSettings
import rj.qmme.data.LoginPrefs
import rj.qmme.data.chat.ChatSettingsRepository
import rj.qmme.data.chat.DraftStore
import rj.qmme.ui.navigation.ViewNavigator
import rj.qmme.viewmodel.AuthViewModel
import rj.qmme.viewmodel.ChatDetailViewModel
import rj.qmme.viewmodel.ChatListViewModel
import rj.qmme.viewmodel.ContactsViewModel

/** Native phone-first Material 3 Expressive launcher. Compose is intentionally not used. */
class MainActivity : AppCompatActivity() {
    private var isShowingLoggedInSurface = false
    private var handledOfficialLogout: Constants.LogoutReason? = null
    private lateinit var screenHost: FrameLayout
    private lateinit var navigator: ViewNavigator
    private var pendingImageViewModel: ChatDetailViewModel? = null
    private var activeChatViewModel: ChatDetailViewModel? = null
    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val viewModel = pendingImageViewModel
            pendingImageViewModel = null
            if (uri != null && viewModel != null) viewModel.sendImage(this, uri)
        }

    private val phoneStatePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i("QMME", "READ_PHONE_STATE permission granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        screenHost = FrameLayout(this)
        setContentView(screenHost)
        navigator = ViewNavigator(this, screenHost)
        observeOfficialLogout()
        ensurePhoneStatePermission()

        LoginPrefs.loadAccount(this)?.let(::showLoggedIn) ?: showLogin()
    }

    private fun ensurePhoneStatePermission() {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            Log.d("QMME", "READ_PHONE_STATE already granted")
            return
        }
        phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
    }

    private fun observeOfficialLogout() {
        launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                QmmeApp.logoutReason.collect { reason ->
                    if (reason == null) {
                        handledOfficialLogout = null
                        return@collect
                    }
                    if (handledOfficialLogout == reason) return@collect
                    handledOfficialLogout = reason

                    LoginPrefs.clear(this@MainActivity)
                    QmmeApp.acknowledgeOfficialLogout(reason)
                    Log.w("QMME", "ui: returned to login after official logout=$reason")

                    if (isShowingLoggedInSurface && !isFinishing && !isDestroyed) recreate()
                }
            }
        }
    }

    private fun showLogin() {
        isShowingLoggedInSurface = false
        val loginScreen = LoginHikagable(this)
        loginScreen.onLoginSuccess = { _, account ->
            LoginPrefs.saveAccount(this, account)
            QmmeApp.markLoginEstablished()
            showLoggedIn(account)
        }
        val hikage = loginScreen.hikage.create(this, screenHost, false)
        val entry = ViewNavigator.Entry(route = ROUTE_LOGIN, view = hikage.root)
        navigator.replaceRoot(entry)
        loginScreen.bind(
            entry.lifecycleOwner,
            ViewModelProvider(this)[AuthViewModel::class.java],
        )
    }

    private fun showLoggedIn(account: SimpleAccount) {
        isShowingLoggedInSurface = true
        val mainScreen = MainHikagable(
            context = this,
            account = account,
            onRequestLogout = { confirmLogout(account) },
            onRequestForceExit = { confirmForceExit() },
            onOpenSettings = { openSettings() },
            onOpenChat = { openChat(account, ChatDetailViewModel.ChatTarget.fromRecent(it)) },
            onOpenContactProfile = { buddy -> openProfile(account, buddy) },
        )
        val hikage = mainScreen.hikage.create(this, screenHost, false)
        val entry = ViewNavigator.Entry(route = ROUTE_MAIN, view = hikage.root)
        navigator.replaceRoot(entry)
        mainScreen.bind(
            entry.lifecycleOwner,
            ViewModelProvider(this)[ChatListViewModel::class.java],
            ViewModelProvider(this)[ContactsViewModel::class.java],
        )
    }

    private fun confirmLogout(account: SimpleAccount) {
        if (!AppSettings.confirmLogout(this)) {
            performLogout()
            return
        }
        val screen = ConfirmActionHikagable(
            context = this,
            title = "退出登录？",
            message = "将清除本机登录状态并返回登录页。账号 ${account.uin} 需要重新扫码登录。",
            confirmLabel = "退出登录",
            destructive = true,
            onBack = { navigator.pop() },
            onConfirm = {
                navigator.pop()
                performLogout()
            },
        )
        pushScreen(ROUTE_CONFIRM, screen)
    }

    private fun confirmForceExit() {
        val screen = ConfirmActionHikagable(
            context = this,
            title = "强制退出应用？",
            message = "将立即结束进程。未发送的草稿会保留在本机，但当前会话不会继续。",
            confirmLabel = "强制退出",
            destructive = true,
            onBack = { navigator.pop() },
            onConfirm = { QmmeApp.forceExit(this) },
        )
        pushScreen(ROUTE_CONFIRM, screen)
    }

    private fun performLogout() {
        (application as? QmmeApp)?.clearLocalLoginState()
        QmmeApp.forceExit(this)
    }

    private fun openSettings() {
        val screen = SettingsHikagable(
            context = this,
            onBack = { navigator.pop() },
            onOpenAbout = { openAbout() },
            onClearDrafts = {
                DraftStore.clearAll(this)
                toast("已清除本地草稿")
            },
            enterToSend = AppSettings.enterToSend(this),
            onEnterToSendChanged = { AppSettings.setEnterToSend(this, it) },
            confirmLogout = AppSettings.confirmLogout(this),
            onConfirmLogoutChanged = { AppSettings.setConfirmLogout(this, it) },
        )
        pushScreen(ROUTE_SETTINGS, screen)
    }

    private fun openAbout() {
        val screen = AboutHikagable(
            context = this,
            onBack = { navigator.pop() },
        )
        pushScreen(ROUTE_ABOUT, screen)
    }

    private fun openProfile(account: SimpleAccount, buddy: ContactsViewModel.UiBuddy) {
        val title = buddy.remark.ifBlank { buddy.nick }.ifBlank { buddy.uin.toString() }
        val screen = ProfileHikagable(
            context = this,
            title = title,
            uid = buddy.uid,
            uin = buddy.uin,
            avatarPath = buddy.avatarPath,
            avatarUrl = buddy.avatarUrls.firstOrNull().orEmpty(),
            subtitle = buddy.categoryName.ifBlank { "好友" },
            onBack = { navigator.pop() },
            onOpenChat = {
                navigator.pop()
                openChat(
                    account,
                    ChatDetailViewModel.ChatTarget(
                        chatType = 1,
                        peerUid = buddy.uid.ifBlank { buddy.uin.toString() },
                        peerUin = buddy.uin,
                        title = title,
                        avatarPath = buddy.avatarPath,
                        avatarUrl = buddy.avatarUrls.firstOrNull().orEmpty(),
                    ),
                )
            },
        )
        val hikage = screen.hikage.create(this, screenHost, false)
        val entry = ViewNavigator.Entry(
            route = ROUTE_PROFILE,
            view = hikage.root,
            disposeAction = screen::dispose,
        )
        navigator.push(entry)
        screen.bind()
    }

    private fun openChat(account: SimpleAccount, target: ChatDetailViewModel.ChatTarget) {
        if (target.peerUid.isBlank()) {
            Log.w("QMME", "ui: refusing chat target without peer uid title=${target.title}")
            return
        }
        val viewModel = ViewModelProvider(this)[ChatDetailViewModel::class.java]
        activeChatViewModel = viewModel
        val screen = ChatDetailHikagable(
            context = this,
            target = target,
            onBack = {
                activeChatViewModel = null
                navigator.pop()
            },
            onPickImage = {
                pendingImageViewModel = viewModel
                imagePicker.launch("image/*")
            },
            onOpenImage = { image, sourceView -> openImagePreview(image, sourceView) },
            onOpenSettings = { openChatSettings(account, target) },
            onOpenSearch = { openChatSearch(viewModel) },
            onForwardMessage = { message ->
                if (viewModel.prepareForward(message)) {
                    openContactPickerForForward(viewModel)
                } else {
                    toast(viewModel.statusText.value.ifBlank { "无法转发" })
                }
            },
        )
        val hikage = screen.hikage.create(this, screenHost, false)
        val entry = ViewNavigator.Entry(
            route = ROUTE_CHAT,
            view = hikage.root,
            disposeAction = {
                activeChatViewModel = null
                viewModel.closeChat()
            },
        )
        navigator.push(entry)
        screen.bind(entry.lifecycleOwner, viewModel, account.uin.toString())
    }

    private fun openChatSettings(account: SimpleAccount, target: ChatDetailViewModel.ChatTarget) {
        val isGroup = target.chatType == 2
        val screen = ChatSettingsHikagable(
            context = this,
            targetTitle = target.title,
            isGroup = isGroup,
            onBack = { navigator.pop() },
            onSetTop = { enabled, done ->
                val started = ChatSettingsRepository.setTop(
                    chatType = target.chatType,
                    peerUid = target.peerUid,
                    peerUin = target.peerUin,
                    enabled = enabled,
                    callback = done,
                )
                if (!started) done(false, "服务不可用")
            },
            onSetMuted = { muted, done ->
                val started = ChatSettingsRepository.setMuted(
                    chatType = target.chatType,
                    peerUid = target.peerUid,
                    peerUin = target.peerUin,
                    muted = muted,
                    callback = done,
                )
                if (!started) done(false, "服务不可用")
            },
            onOpenMembers = if (isGroup) {
                {
                    navigator.pop()
                    openGroupMembers(account, target)
                }
            } else {
                null
            },
            onOpenProfile = if (!isGroup) {
                {
                    navigator.pop()
                    openProfile(
                        account,
                        ContactsViewModel.UiBuddy(
                            uid = target.peerUid,
                            uin = target.peerUin,
                            nick = target.title,
                            remark = target.title,
                            avatarPath = target.avatarPath,
                            avatarUrls = listOfNotNull(target.avatarUrl.takeIf { it.isNotBlank() }),
                            categoryId = 0,
                            categoryName = "好友",
                        ),
                    )
                }
            } else {
                null
            },
        )
        pushScreen(ROUTE_CHAT_SETTINGS, screen)
    }

    private fun openGroupMembers(account: SimpleAccount, target: ChatDetailViewModel.ChatTarget) {
        val groupCode = target.peerUin.takeIf { it > 0L }
            ?: target.peerUid.toLongOrNull()
            ?: 0L
        val screen = GroupMembersHikagable(
            context = this,
            groupCode = groupCode,
            groupTitle = target.title,
            onBack = { navigator.pop() },
            onOpenMember = { uid, uin, name, avatarPath ->
                openProfile(
                    account,
                    ContactsViewModel.UiBuddy(
                        uid = uid,
                        uin = uin,
                        nick = name,
                        remark = name,
                        avatarPath = avatarPath,
                        avatarUrls = emptyList(),
                        categoryId = 0,
                        categoryName = "群成员",
                    ),
                )
            },
        )
        val hikage = screen.hikage.create(this, screenHost, false)
        val entry = ViewNavigator.Entry(route = ROUTE_GROUP_MEMBERS, view = hikage.root)
        navigator.push(entry)
        screen.bind(entry.lifecycleOwner)
    }

    private fun openChatSearch(viewModel: ChatDetailViewModel) {
        val screen = ChatSearchHikagable(
            context = this,
            onBack = { navigator.pop() },
            onResultClick = { messageId ->
                navigator.pop()
                toast("已定位消息")
                // Jump support can be extended once MessageAdapter exposes scroll-to-id.
                Log.d("QMME", "search result messageId=$messageId")
            },
            searchFn = viewModel::searchLoaded,
        )
        val hikage = screen.hikage.create(this, screenHost, false)
        val entry = ViewNavigator.Entry(
            route = ROUTE_CHAT_SEARCH,
            view = hikage.root,
            disposeAction = screen::dispose,
        )
        navigator.push(entry)
    }

    private fun openContactPickerForForward(viewModel: ChatDetailViewModel) {
        val contactsViewModel = ViewModelProvider(this)[ContactsViewModel::class.java]
        val screen = ContactPickerHikagable(
            context = this,
            title = "转发到…",
            onBack = {
                viewModel.clearPendingForward()
                navigator.pop()
            },
            onPick = { chatType, peerUid, title ->
                navigator.pop()
                if (viewModel.forwardPendingTo(chatType, peerUid)) {
                    toast("正在转发到 $title")
                } else {
                    toast(viewModel.statusText.value.ifBlank { "转发失败" })
                }
            },
        )
        val hikage = screen.hikage.create(this, screenHost, false)
        val entry = ViewNavigator.Entry(route = ROUTE_CONTACT_PICKER, view = hikage.root)
        navigator.push(entry)
        screen.bind(entry.lifecycleOwner, contactsViewModel)
    }

    private fun openImagePreview(image: ChatDetailViewModel.UiImage, sourceView: View?) {
        val screen = ImagePreviewHikagable(
            context = this,
            image = image,
            onBack = { navigator.pop(imageCollapseTransform(sourceView)) },
        )
        val hikage = screen.hikage.create(this, screenHost, false)
        val entry = ViewNavigator.Entry(
            route = ROUTE_IMAGE,
            view = hikage.root,
            disposeAction = screen::dispose,
        )
        navigator.push(entry, imageExpandTransform(sourceView, hikage.root))
        screen.bind()
    }

    private fun pushScreen(route: String, screen: HikageScreen) {
        val hikage = screen.hikage.create(this, screenHost, false)
        navigator.push(ViewNavigator.Entry(route = route, view = hikage.root))
    }

    private fun imageExpandTransform(source: View?, endView: View): MaterialContainerTransform? {
        source ?: return null
        return MaterialContainerTransform().apply {
            startView = source
            this.endView = endView
            addTarget(endView)
            scrimColor = Color.TRANSPARENT
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }
    }

    private fun imageCollapseTransform(source: View?): MaterialContainerTransform? {
        val target = source?.takeIf { it.isAttachedToWindow } ?: return null
        val previewRoot = screenHost.getChildAt(screenHost.childCount - 1) ?: return null
        return MaterialContainerTransform().apply {
            startView = previewRoot
            endView = target
            addTarget(target)
            scrimColor = Color.TRANSPARENT
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }
    }

    private companion object {
        const val ROUTE_LOGIN = "login"
        const val ROUTE_MAIN = "main"
        const val ROUTE_CHAT = "chat"
        const val ROUTE_IMAGE = "image"
        const val ROUTE_SETTINGS = "settings"
        const val ROUTE_ABOUT = "about"
        const val ROUTE_CONFIRM = "confirm"
        const val ROUTE_PROFILE = "profile"
        const val ROUTE_CHAT_SETTINGS = "chat_settings"
        const val ROUTE_GROUP_MEMBERS = "group_members"
        const val ROUTE_CHAT_SEARCH = "chat_search"
        const val ROUTE_CONTACT_PICKER = "contact_picker"
    }
}
