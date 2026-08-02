package rj.qmme.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.highcapable.hikage.extension.setContentView
import rj.qmme.viewmodel.GroupManagementViewModel

/** Hosts the phone group management screen. */
class GroupManagementActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_GROUP_CODE = "group_code"
        const val EXTRA_GROUP_TITLE = "group_title"

        fun intent(context: Context, groupCode: Long, title: String): Intent =
            Intent(context, GroupManagementActivity::class.java).apply {
                putExtra(EXTRA_GROUP_CODE, groupCode)
                putExtra(EXTRA_GROUP_TITLE, title)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val groupCode = intent.getLongExtra(EXTRA_GROUP_CODE, 0L)
        val groupTitle = intent.getStringExtra(EXTRA_GROUP_TITLE).orEmpty()

        val viewModel = ViewModelProvider(this)[GroupManagementViewModel::class.java]
        val screen = GroupManagementHikagable(
            context = this,
            onBack = { finish() },
        )
        setContentView(screen.hikage)
        screen.bind(
            owner = this,
            viewModel = viewModel,
            groupCode = groupCode,
            groupTitle = groupTitle,
        )
    }
}
