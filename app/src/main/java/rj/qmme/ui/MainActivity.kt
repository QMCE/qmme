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
import com.tencent.qphone.base.remote.SimpleAccount
import mqq.app.Constants
import rj.qmme.QmmeApp
import rj.qmme.data.LoginPrefs
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
    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val viewModel = pendingImageViewModel
            pendingImageViewModel = null
            if (uri != null && viewModel != null) viewModel.sendImage(this, uri)
        }

    // READ_PHONE_STATE feeds the QIMEI/Beacon device fingerprint. Request it before
    // login so QQBeaconPrivateInfo can read the device identifier; without it QIMEI
    // registration fails and the server instantly kicks the session.
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

    /**
     * Request READ_PHONE_STATE at runtime. This dangerous permission gates the
     * device identifier the QIMEI/Beacon SDK needs to register a stable device
     * fingerprint with the backend. Requested early (before login) so the
     * fingerprint is ready by the time WtLogin/MSF attach QIMEI to SSO packets.
     */
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

    /** Mirrors the official logout reason observer using the native lifecycle. */
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
            onLogout = {
                (application as? QmmeApp)?.clearLocalLoginState()
                QmmeApp.forceExit(this)
            },
            onForceExit = { QmmeApp.forceExit(this) },
            onOpenChat = { openChat(account, ChatDetailViewModel.ChatTarget.fromRecent(it)) },
            onOpenContactChat = { buddy ->
                openChat(
                    account,
                    ChatDetailViewModel.ChatTarget(
                        chatType = 1,
                        peerUid = buddy.uid.ifBlank { buddy.uin.toString() },
                        peerUin = buddy.uin,
                        title = buddy.remark.ifBlank { buddy.nick }
                            .ifBlank { buddy.uin.toString() },
                        avatarPath = buddy.avatarPath,
                        avatarUrl = buddy.avatarUrls.firstOrNull().orEmpty(),
                    ),
                )
            },
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

    private fun openChat(account: SimpleAccount, target: ChatDetailViewModel.ChatTarget) {
        if (target.peerUid.isBlank()) {
            Log.w("QMME", "ui: refusing chat target without peer uid title=${target.title}")
            return
        }
        val viewModel = ViewModelProvider(this)[ChatDetailViewModel::class.java]
        val screen = ChatDetailHikagable(
            context = this,
            target = target,
            onBack = { navigator.pop() },
            onPickImage = {
                pendingImageViewModel = viewModel
                imagePicker.launch("image/*")
            },
            onOpenImage = { image, sourceView -> openImagePreview(image, sourceView) },
        )
        val hikage = screen.hikage.create(this, screenHost, false)
        val entry = ViewNavigator.Entry(
            route = ROUTE_CHAT,
            view = hikage.root,
            disposeAction = { viewModel.closeChat() },
        )
        navigator.push(entry)
        screen.bind(entry.lifecycleOwner, viewModel, account.uin.toString())
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

    /**
     * M3 container transform: the tapped bubble image expands into the
     * full-screen preview. Falls back to the navigator's default shared-axis
     * transition when the source view is gone (e.g. recycled off-screen).
     */
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
    }
}
