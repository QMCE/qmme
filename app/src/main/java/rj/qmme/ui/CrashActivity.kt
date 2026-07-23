package rj.qmme.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.highcapable.hikage.extension.setContentView
import rj.qmme.CrashCatcher

/** Hosts the native Hikage crash report screen. */
class CrashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(CrashHikagable(this, CrashCatcher.readLatestReport(this, intent)).hikage)
    }
}
