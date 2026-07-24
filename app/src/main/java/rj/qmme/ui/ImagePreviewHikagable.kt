package rj.qmme.ui

import android.content.Context
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.FrameLayout as HFrameLayout
import com.highcapable.hikage.widget.android.widget.LinearLayout as HLinearLayout
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar as HMaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.imageview.ShapeableImageView as HShapeableImageView
import rj.qmme.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rj.qmme.data.media.MediaStoreSaver
import rj.qmme.viewmodel.ChatDetailViewModel

/** Simple full-screen native image preview used by the first rich-media slice. */
class ImagePreviewHikagable(
    private val context: Context,
    private val image: ChatDetailViewModel.UiImage,
    private val onBack: () -> Unit,
) : HikageScreen {
    private lateinit var imageView: ShapeableImageView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var saveItem: MenuItem
    private val mediaStoreSaver = MediaStoreSaver()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var saveJob: Job? = null

    override val hikage: Hikage.Delegate<*> = Hikagable {
        HLinearLayout(
            lparams = LayoutParams(matchParent = true),
            init = {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorSurface,
                    ),
                )
            },
        ) {
            HLinearLayout(
                lparams = LayoutParams(widthMatchParent = true, height = 0),
                init = { EdgeToEdgeInsets.applyTopInsetSpacer(this) },
            )
            toolbar = HMaterialToolbar(
                lparams = LayoutParams(widthMatchParent = true),
                init = {
                    title = "图片"
                    navigationIcon = ContextCompat.getDrawable(context, R.drawable.ic_arrow_back)
                    setNavigationContentDescription("返回")
                    setNavigationOnClickListener { onBack() }
                    saveItem = menu.add(Menu.NONE, MENU_SAVE, Menu.NONE, "保存")
                    saveItem.setIcon(R.drawable.ic_download)
                    saveItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                    setOnMenuItemClickListener { item ->
                        if (item.itemId == MENU_SAVE) {
                            saveImage()
                            true
                        } else {
                            false
                        }
                    }
                    EdgeToEdgeInsets.applyHorizontalInsets(this)
                },
            )
            HFrameLayout(
                lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
                init = {
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    EdgeToEdgeInsets.applyHorizontalInsets(this)
                },
            ) {
                imageView = HShapeableImageView(
                    lparams = LayoutParams(matchParent = true),
                    init = {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        adjustViewBounds = true
                        contentDescription = "图片预览"
                        setBackgroundColor(
                            MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorSurfaceContainerLowest,
                            ),
                        )
                    },
                )
            }
            HLinearLayout(
                lparams = LayoutParams(widthMatchParent = true, height = 0),
                init = { EdgeToEdgeInsets.applyBottomInsetSpacer(this) },
            )
        }
    }

    fun bind() {
        AvatarLoader.bind(
            imageView = imageView,
            localPath = image.localPaths.firstOrNull(String::isNotBlank),
            urls = image.remoteUrls,
            fallback = null,
            circular = false,
        )
    }

    fun dispose() {
        saveJob?.cancel()
        scope.cancel()
        AvatarLoader.unbind(imageView)
    }

    private fun saveImage() {
        if (saveJob?.isActive == true) return
        val sources = (image.localPaths + image.remoteUrls)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        if (sources.isEmpty()) {
            showSaveResult("图片地址不可用")
            return
        }
        saveItem.isEnabled = false
        saveJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                var last: Result<Unit> = Result.failure(IllegalStateException("图片不可用"))
                for (source in sources) {
                    last = mediaStoreSaver.saveImage(context, source)
                    if (last.isSuccess) break
                }
                last
            }
            saveItem.isEnabled = true
            showSaveResult(
                result.exceptionOrNull()?.message?.takeIf(String::isNotBlank)
                    ?.let { "保存失败：$it" } ?: "图片已保存到 Pictures/QMME",
            )
        }
    }

    private fun showSaveResult(message: String) {
        MaterialAlertDialogBuilder(context)
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val MENU_SAVE = 1
    }
}
