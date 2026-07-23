package rj.qmme.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.highcapable.hikage.extension.setContentView
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import mqq.app.Constants
import rj.qmme.QmmeApp
import rj.qmme.data.LoginPrefs
import rj.qmme.viewmodel.AuthViewModel
import rj.qmme.viewmodel.ChatListViewModel
import rj.qmme.viewmodel.ContactsViewModel

/** Native Material 3/Hikage-compatible launcher. Compose is intentionally not used. */
class MainActivity : AppCompatActivity() {
    private var isShowingLoggedInSurface = false
    private var handledOfficialLogout: Constants.LogoutReason? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        observeOfficialLogout()

        LoginPrefs.loadAccount(this)?.let(::showLoggedIn) ?: showLogin()
    }

    /**
     * Mirrors QMCE-Lite-X's logoutReason observer using the native lifecycle.
     * Recreating the Activity disposes Hikage bindings that were started with
     * this Activity's lifecycle before the logged-out UI is rendered.
     */
    private fun observeOfficialLogout() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                QmmeApp.logoutReason.collect { reason ->
                    if (reason == null) {
                        handledOfficialLogout = null
                        return@collect
                    }
                    if (handledOfficialLogout == reason) return@collect
                    handledOfficialLogout = reason

                    // QmmeApp's official IAccountCallback has already released
                    // MobileQQ state. Repeat the durable erase defensively here
                    // before replacing the active signed-in screen.
                    LoginPrefs.clear(this@MainActivity)
                    QmmeApp.acknowledgeOfficialLogout(reason)
                    Log.w("QMME", "ui: returned to login after official logout=$reason")

                    if (isShowingLoggedInSurface && !isFinishing && !isDestroyed) {
                        recreate()
                    }
                }
            }
        }
    }

    private fun showLogin() {
        isShowingLoggedInSurface = false
        val loginScreen = LoginHikagable(this)
        loginScreen.onLoginSuccess = { _, account ->
            // Must be durable before restartAfterLogin terminates this process.
            LoginPrefs.saveAccount(this, account)
            QmmeApp.markLoginEstablished()
            val restarted = QmmeApp.restartAfterLogin(this)
            if (!restarted) {
                showLoggedIn(account)
            }
        }
        setContentView(loginScreen.hikage)
        loginScreen.bind(this, ViewModelProvider(this)[AuthViewModel::class.java])
    }

    private fun showLoggedIn(account: com.tencent.qphone.base.remote.SimpleAccount) {
        isShowingLoggedInSurface = true
        val mainScreen = MainHikagable(
            context = this,
            account = account,
            onLogout = {
                (application as? QmmeApp)?.clearLocalLoginState()
                QmmeApp.forceExit(this)
            },
            onForceExit = { QmmeApp.forceExit(this) },
        )
        setContentView(mainScreen.hikage)
        mainScreen.bind(
            this,
            ViewModelProvider(this)[ChatListViewModel::class.java],
            ViewModelProvider(this)[ContactsViewModel::class.java],
        )
    }
}
