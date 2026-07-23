package rj.qmme.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.highcapable.hikage.extension.setContentView
import rj.qmme.QmmeApp
import rj.qmme.data.LoginPrefs
import rj.qmme.viewmodel.AuthViewModel
import rj.qmme.viewmodel.ChatListViewModel
import rj.qmme.viewmodel.ContactsViewModel

/** Native Material 3/Hikage-compatible launcher. Compose is intentionally not used. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val stored = LoginPrefs.loadAccount(this)
        if (stored == null) {
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
        } else {
            showLoggedIn(stored)
        }
    }

    private fun showLoggedIn(account: com.tencent.qphone.base.remote.SimpleAccount) {
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
