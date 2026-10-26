package app.lifeboat.vault

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

class ViewerActivity : AppCompatActivity() {
    private var temp: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!LifeboatApp.unlocked) { finish(); return }
        val v = try { Vault.get(this) } catch (e: Exception) { finish(); return }
        val item = intent.getStringExtra("id")?.let { v.find(it) } ?: run { finish(); return }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val spinner = ProgressBar(this)
        root.addView(spinner, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        val caption = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0x88000000.toInt()); setPadding(32, 20, 32, 20)
            text = "${item.name}\n${formatDate(item.taken)} · ${humanBytes(item.size)}"
        }
        root.addView(caption, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            caption.setPadding(32, 20, 32, 20 + b.bottom)
            insets
        }
        setContentView(root)

        val ext = item.name.substringAfterLast('.', "bin")
        val f = File(Vault.plainDir(this), "view-${item.id}.$ext").also { temp = it }
        Thread {
            val ok = try { v.decryptToFile(item, f) } catch (e: Exception) { false }
            val bmp = if (ok && !item.isVideo) {
                val dm = resources.displayMetrics
                Media.decodeSampled(f, maxOf(dm.widthPixels, dm.heightPixels)).also { f.delete() }
            } else null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                root.removeView(spinner)
                when {
                    !ok -> caption.text = "⚠️ This file failed its check. Restore it from a backup."
                    item.isVideo -> {
                        val vv = VideoView(this)
                        root.addView(vv, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
                        val mc = MediaController(this); mc.setAnchorView(vv); vv.setMediaController(mc)
                        vv.setVideoPath(f.path); vv.start()
                        caption.text = "${caption.text} · ✓ verified"
                    }
                    bmp != null -> {
                        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(bmp) }
                        root.addView(iv, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                        caption.text = "${caption.text} · ✓ verified"
                    }
                    else -> caption.text = "${caption.text}\n(Can't preview this format on this phone, but the file is safe. Use 'Save a copy out'.)"
                }
            }
        }.start()
    }

    override fun onDestroy() {
        temp?.delete()
        super.onDestroy()
    }
}
