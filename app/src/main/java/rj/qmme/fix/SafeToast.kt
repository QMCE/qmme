package rj.qmme.fix

import android.content.Context
import com.highcapable.betterandroid.ui.extension.view.toast

// QQ Toast redirect
@Suppress("unused")
object SafeToast {
    @JvmStatic
    fun show(context: Context?, message: CharSequence?, duration: Int, style: Int) {
        if (context == null || message.isNullOrEmpty()) return
        context.applicationContext.toast(message, duration)
    }
}
