package app.lifeboat.vault

import android.Manifest
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.app.PendingIntent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var content: View
    private lateinit var lockedView: View
    private lateinit var stats: TextView
    private lateinit var progressBox: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var stopBtn: MaterialButton
    private lateinit var empty: TextView
    private lateinit var grid: RecyclerView
    private val adapter = ThumbAdapter()
    private var shownSeq = -1
    private var noLockWarned = false
    private var pendingBackupPassword: CharArray? = null
    private var pendingExportIds: List<String>? = null

    private val dp get() = resources.displayMetrics.density
    private fun Int.px() = (this * dp).toInt()

    // ---------- pickers ----------
    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) run("Saving ${uris.size} photos/videos") { Importer.importUris(this, uris, it) }
    }
    /** Opens the file picker straight in Downloads, where Google's Takeout files land. */
    private class OpenInDownloads : ActivityResultContracts.OpenMultipleDocuments() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).putExtra(
                android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
            )
    }
    private val pickZips = registerForActivityResult(OpenInDownloads()) { uris ->
        if (uris.isNotEmpty()) run("Bringing in your Google Photos") { Importer.importTakeout(this, uris, it) }
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) run("Saving the folder") { Importer.importTree(this, uri, it) }
    }
    private val pickCheck = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) run("Checking ${uris.size} photos") { Importer.checkSafe(this, uris, it) }
    }
    private val pickExportFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val ids = pendingExportIds
        pendingExportIds = null
        if (uri != null) run("Saving copies out") { Tools.export(this, uri, ids, it) }
    }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = pendingBackupPassword
        pendingBackupPassword = null
        if (uri != null && pw != null) run("Backing up the vault") { Tools.backup(this, uri, pw, it) }
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) askPassword("Backup password", confirm = false) { pw ->
            run("Restoring backup") {
                try { Tools.restore(this, uri, pw, it) } catch (e: Tools.WrongPassword) { "❌ Wrong password. Nothing was changed." }
            }
        }
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val jobListener: (Jobs.State) -> Unit = { s -> onJob(s) }

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) // no screenshots, blank in recent apps
        setContentView(buildUi())
    }

    override fun onStart() { super.onStart(); Jobs.listen(jobListener) }
    override fun onStop() { Jobs.unlisten(jobListener); super.onStop() }

    override fun onResume() {
        super.onResume()
        if (LifeboatApp.unlocked) showContent() else { showLocked(); unlock() }
    }

    // ---------- lock ----------
    private fun unlock() {
        val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            LifeboatApp.unlocked = true
            showContent()
            if (!noLockWarned) {
                noLockWarned = true
                Toast.makeText(this, "Tip: set a screen lock on your phone so Lifeboat can lock the vault.", Toast.LENGTH_LONG).show()
            }
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                LifeboatApp.unlocked = true
                showContent()
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Unlock your vault").setAllowedAuthenticators(auth).build()
        )
    }

    private fun showLocked() { content.visibility = View.GONE; lockedView.visibility = View.VISIBLE }

    private fun showContent() {
        lockedView.visibility = View.GONE
        content.visibility = View.VISIBLE
        refresh()
    }

    // ---------- UI ----------
    private fun buildUi(): View {
        val frame = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16.px(), 12.px(), 16.px(), 0) }
        content = col

        col.addView(TextView(this).apply {
            text = "Lifeboat"; textSize = 28f; setTypeface(typeface, Typeface.BOLD)
        })
        stats = TextView(this).apply { textSize = 14f; alpha = 0.8f }
        col.addView(stats)
        col.addView(TextView(this).apply {
            text = "🧱 No internet · Encrypted · Invisible to Google Photos & gallery apps"
            textSize = 12f; alpha = 0.65f; setPadding(0, 2.px(), 0, 10.px())
            setOnClickListener { aboutWall() }
        })

        col.addView(MaterialButton(this).apply {
            text = "Rescue my Google Photos"; textSize = 17f
            minHeight = 60.px()
            setOnClickListener { rescueGuide() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun small(label: String, click: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; textSize = 13f; isAllCaps = false; maxLines = 1
            setPadding(4.px(), paddingTop, 4.px(), paddingBottom); setOnClickListener { click() }
        }
        val lp = { w: Float -> LinearLayout.LayoutParams(0, WRAP_CONTENT, w).apply { marginEnd = 6.px() } }
        row.addView(small("＋ Add") { addMenu() }, lp(1f))
        row.addView(small("Safe to delete?") { safeToDelete() }, lp(1.7f))
        row.addView(small("More") { moreMenu() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(row)

        progressBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, 10.px(), 0, 6.px())
        }
        progressText = TextView(this).apply { textSize = 14f }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBox.addView(progressText)
        progressBox.addView(progressBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        stopBtn = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Stop"; isAllCaps = false; visibility = View.GONE
            setOnClickListener { Jobs.cancelRequested = true; text = "Stopping…" }
        }
        progressBox.addView(stopBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        col.addView(progressBox)

        empty = TextView(this).apply {
            textSize = 15f; gravity = Gravity.CENTER; setPadding(12.px(), 40.px(), 12.px(), 0); alpha = 0.75f
            text = "Your vault is empty.\n\nTap \"Rescue my Google Photos\" to pull your whole library out of Google, " +
                "or \"＋ Add\" to save photos from your phone or SD card.\n\nOnce a photo is in here, nothing Google, Motorola " +
                "or any trash can does can touch it."
        }
        col.addView(empty)

        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            adapter = this@MainActivity.adapter
            clipToPadding = false
        }
        col.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = 8.px() })
        frame.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val locked = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(TextView(this@MainActivity).apply { text = "🔒"; textSize = 56f; gravity = Gravity.CENTER })
            addView(TextView(this@MainActivity).apply { text = "Vault locked"; textSize = 20f; gravity = Gravity.CENTER })
            addView(MaterialButton(this@MainActivity).apply { text = "Unlock"; setOnClickListener { unlock() } })
        }
        lockedView = locked
        frame.addView(locked, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, 0)
            grid.setPadding(0, 0, 0, b.bottom)
            insets
        }
        return frame
    }

    private fun refresh() {
        val v = try { Vault.get(this) } catch (e: Exception) {
            stats.text = "⚠️ ${e.message}"
            adapter.submit(emptyList()); empty.visibility = View.GONE
            return
        }
        val items = v.list()
        val where = if (Vault.location(this) == "sd") "SD card" else "phone"
        stats.text = "${items.size} photos & videos · ${humanBytes(v.totalBytes())} · on the $where"
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.submit(items)
    }

    // ---------- jobs ----------
    private fun run(title: String, work: (Reporter) -> String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (!Jobs.start(this, title, work)) Toast.makeText(this, "Wait for the current job to finish first.", Toast.LENGTH_SHORT).show()
    }

    private var lastRefresh = 0L
    private fun onJob(s: Jobs.State) {
        if (s.running) {
            progressBox.visibility = View.VISIBLE
            progressText.text = buildString {
                append(s.title)
                if (s.pct) append(" — ${s.done / 10}%") else if (s.total > 0) append(" — ${s.done} of ${s.total}")
                if (s.current.isNotEmpty()) append("\n${s.current}")
            }
            stopBtn.visibility = if (s.pct) View.VISIBLE else View.GONE
            if (s.pct && !Jobs.cancelRequested) stopBtn.text = "Stop"
            progressBar.isIndeterminate = s.total == 0
            progressBar.max = maxOf(1, s.total)
            progressBar.progress = s.done
            val now = System.currentTimeMillis()
            if (now - lastRefresh > 3000 && LifeboatApp.unlocked) { lastRefresh = now; refresh() }
        } else {
            progressBox.visibility = View.GONE
            if (s.result != null && s.seq != shownSeq) {
                shownSeq = s.seq
                if (LifeboatApp.unlocked) refresh()
                AlertDialog.Builder(this).setTitle(s.title).setMessage(s.result).setPositiveButton("OK", null).show()
            }
        }
    }

    // ---------- menus ----------
    private fun rescueGuide() {
        AlertDialog.Builder(this)
            .setTitle("Rescue my Google Photos")
            .setMessage(
                "Google only lets your whole library out through its own export page, so we'll use it:\n\n" +
                "1️⃣  Tap \"Open Google export\". Only Google Photos is selected.\n" +
                "2️⃣  Tap \"Next step\". Keep \"Send download link via email\" and choose .zip and 50 GB. Then tap \"Create export\".\n" +
                "3️⃣  Google emails you when it's ready (minutes to a day or two). Tap each download link. The files land in Downloads.\n" +
                "4️⃣  Come back here, tap \"Bring them in\" and select all the takeout files.\n\n" +
                "Lifeboat pulls every photo and video straight out of the zips, checks each one, fixes the dates and puts them behind the wall."
            )
            .setPositiveButton("Open Google export") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://takeout.google.com/settings/takeout/custom/photos")))
                } catch (e: Exception) {
                    Toast.makeText(this, "Open takeout.google.com in your browser", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Bring them in") { _, _ -> pickZips.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
            .show()
    }

    private fun addMenu() {
        val opts = arrayOf(
            "Photos & videos (phone, SD card or Google Photos)",
            "A whole folder (e.g. DCIM on your SD card)",
            "Google Takeout zip files",
            "🛟 Recover DELETED photos from an SD card (USB card reader)"
        )
        AlertDialog.Builder(this).setTitle("Add to the vault").setItems(opts) { _, w ->
            when (w) {
                0 -> pickMedia.launch(arrayOf("image/*", "video/*"))
                1 -> pickFolder.launch(null)
                2 -> pickZips.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                3 -> recoverGuide()
            }
        }.show()
    }

    // ---------- deleted-photo recovery through a USB card reader ----------
    private val usbAction by lazy { "$packageName.USB_PERMISSION" }
    private var usbReceiver: BroadcastReceiver? = null

    private fun recoverGuide() {
        AlertDialog.Builder(this)
            .setTitle("Recover deleted photos")
            .setMessage(
                "Deleted photos often stay on an SD card until something overwrites them. Lifeboat can dig them out.\n\n" +
                "Android won't let any app read the deleted parts of the card in the phone's own slot, so:\n\n" +
                "1️⃣  Take the SD card out of the phone (Settings → Storage → Eject first).\n" +
                "2️⃣  Put it in a USB card reader and plug that into the phone (use an OTG adapter if needed).\n" +
                "3️⃣  If Android asks to FORMAT or \"fix\" the card, tap Cancel. Formatting can destroy what's left.\n" +
                "4️⃣  Tap \"Scan the card\" and allow access.\n\n" +
                "Lifeboat only READS the card and never writes to it. Everything it finds is checked and goes straight into the vault. " +
                "Big cards take a while (roughly 1–3 min per GB). Keep the phone plugged in."
            )
            .setPositiveButton("Scan the card") { _, _ -> pickUsbDevice() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pickUsbDevice() {
        val devices = UsbReader.find(this)
        when {
            devices.isEmpty() -> AlertDialog.Builder(this).setTitle("No card reader found")
                .setMessage("Plug the USB card reader (with the SD card in it) into the phone, wait a few seconds, then try again.\n\nIf nothing happens, try another OTG adapter or reader. Some cheap adapters don't support data.")
                .setPositiveButton("Try again") { _, _ -> pickUsbDevice() }
                .setNegativeButton("Cancel", null).show()
            devices.size == 1 -> askUsbPermission(devices[0])
            else -> AlertDialog.Builder(this).setTitle("Which one has the SD card?")
                .setItems(devices.map { UsbReader.label(it) }.toTypedArray()) { _, w -> askUsbPermission(devices[w]) }.show()
        }
    }

    private fun askUsbPermission(d: UsbDevice) {
        val um = getSystemService(UsbManager::class.java)
        if (um.hasPermission(d)) { startRecovery(d); return }
        usbReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                try { unregisterReceiver(this) } catch (_: Exception) {}
                usbReceiver = null
                if (um.hasPermission(d)) startRecovery(d)
                else Toast.makeText(this@MainActivity, "Lifeboat needs that permission to read the card.", Toast.LENGTH_LONG).show()
            }
        }
        usbReceiver = rcv
        ContextCompat.registerReceiver(this, rcv, IntentFilter(usbAction), ContextCompat.RECEIVER_NOT_EXPORTED)
        val pi = PendingIntent.getBroadcast(this, 7, Intent(usbAction).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        um.requestPermission(d, pi)
    }

    private fun startRecovery(d: UsbDevice) {
        run("Recovering deleted photos") { Recovery.fromUsb(applicationContext, d, it) }
    }

    private fun safeToDelete() {
        AlertDialog.Builder(this)
            .setTitle("Safe to delete?")
            .setMessage("Pick the photos you want to delete from Google Photos or your phone. Lifeboat checks each one, byte for byte, against the vault and tells you which ones are safe to delete and which aren't.")
            .setPositiveButton("Pick photos") { _, _ -> pickCheck.launch(arrayOf("image/*", "video/*")) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun moreMenu() {
        val sd = Vault.sdRoot(this) != null
        val onSd = Vault.location(this) == "sd"
        val opts = mutableListOf(
            "Back up the vault (password-locked file)",
            "Restore a backup",
            "Check every file",
            "Save copies of everything out (by year)",
        )
        if (onSd) opts += "Move vault to the phone" else if (sd) opts += "Move vault to the SD card"
        opts += "About the wall"
        AlertDialog.Builder(this).setItems(opts.toTypedArray()) { _, w ->
            when (opts[w]) {
                opts[0] -> askPassword("Choose a backup password", confirm = true) { pw ->
                    pendingBackupPassword = pw
                    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    createBackup.launch("lifeboat-backup-$stamp.lifeboat")
                }
                opts[1] -> openBackup.launch(arrayOf("*/*"))
                opts[2] -> run("Checking every file") { Tools.verifyAll(this, it) }
                opts[3] -> { pendingExportIds = null; pickExportFolder.launch(null) }
                "Move vault to the phone" -> confirmMove("phone")
                "Move vault to the SD card" -> confirmMove("sd")
                "About the wall" -> aboutWall()
            }
        }.show()
    }

    private fun confirmMove(to: String) {
        AlertDialog.Builder(this)
            .setTitle(if (to == "sd") "Move to SD card?" else "Move to phone?")
            .setMessage("Everything is copied and checked first. The old copy is deleted only after that.\n\nNote: Android deletes an app's files, on the SD card too, if you uninstall the app. Keep a backup.")
            .setPositiveButton("Move") { _, _ -> run("Moving the vault") { Tools.move(this, to, it) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun aboutWall() {
        AlertDialog.Builder(this).setTitle("The wall").setMessage(
            "• No internet permission. Lifeboat physically can't upload or sync anything. Android blocks it.\n\n" +
            "• Invisible. Vault files are encrypted, have no file names or extensions and sit in a private app folder that Google Photos, Motorola's gallery and the media scanner skip.\n\n" +
            "• No cloud backup. Android/Google backup is switched off for this app.\n\n" +
            "• Verified copies. Every photo is copied, read back and compared to the original's fingerprint (SHA-256) before it counts as saved.\n\n" +
            "• Trash-proof. Deleting or emptying a trash in Google Photos, Gallery or Files can't touch what's in here.\n\n" +
            "• Locked. Uses your phone's screen lock or fingerprint. Screenshots are blocked.\n\n" +
            "⚠️ One rule: if you uninstall Lifeboat, Android deletes the vault. Use \"Back up the vault\" and keep the file on a USB stick or computer."
        ).setPositiveButton("OK", null).show()
    }

    private fun askPassword(title: String, confirm: Boolean, done: (CharArray) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24.px(), 8.px(), 24.px(), 0) }
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val p1 = field("Password"); box.addView(p1)
        val p2 = field("Same password again"); if (confirm) box.addView(p2)
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setMessage(if (confirm) "Write it down. Nobody can reset it, not even us.\n\nOn the next screen, save the file to Downloads. Then copy it to a USB stick or computer." else null)
            .setPositiveButton("OK") { _, _ ->
                val a = p1.text.toString()
                when {
                    a.length < 6 -> Toast.makeText(this, "Use at least 6 characters", Toast.LENGTH_LONG).show()
                    confirm && a != p2.text.toString() -> Toast.makeText(this, "Passwords don't match", Toast.LENGTH_LONG).show()
                    else -> done(a.toCharArray())
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun itemMenu(item: Item) {
        AlertDialog.Builder(this).setTitle(item.name)
            .setMessage("${formatDate(item.taken)} · ${humanBytes(item.size)}\nFingerprint: ${item.sha256.take(16)}…")
            .setItems(arrayOf("Save a copy out", "Delete from vault")) { _, w ->
                when (w) {
                    0 -> { pendingExportIds = listOf(item.id); pickExportFolder.launch(null) }
                    1 -> AlertDialog.Builder(this).setTitle("Delete from the vault?")
                        .setMessage("This deletes Lifeboat's copy permanently. There's no trash can here, on purpose.")
                        .setPositiveButton("Delete") { _, _ -> Vault.get(this).delete(listOf(item.id)); refresh() }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    // ---------- grid ----------
    private val thumbPool = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    inner class ThumbAdapter : RecyclerView.Adapter<ThumbAdapter.VH>() {
        private var items: List<Item> = emptyList()
        fun submit(list: List<Item>) { items = list; notifyDataSetChanged() }

        inner class VH(val root: SquareFrame, val img: ImageView, val badge: TextView) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val f = SquareFrame(parent.context).apply { setPadding(2, 2, 2, 2) }
            val img = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0x22888888) }
            val badge = TextView(parent.context).apply {
                text = "▶"; setTextColor(Color.WHITE); textSize = 14f; setShadowLayer(4f, 0f, 0f, Color.BLACK); setPadding(8, 4, 8, 4)
            }
            f.addView(img, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            f.addView(badge, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
            return VH(f, img, badge)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val it = items[pos]
            h.badge.visibility = if (it.isVideo) View.VISIBLE else View.GONE
            h.img.tag = it.id
            val cached = cache.get(it.id)
            if (cached != null) h.img.setImageBitmap(cached) else {
                h.img.setImageDrawable(null)
                val v = Vault.get(this@MainActivity)
                thumbPool.execute {
                    val b = v.thumb(it.id) ?: return@execute
                    cache.put(it.id, b)
                    h.img.post { if (h.img.tag == it.id) h.img.setImageBitmap(b) }
                }
            }
            h.root.setOnClickListener { _ -> startActivity(Intent(this@MainActivity, ViewerActivity::class.java).putExtra("id", it.id)) }
            h.root.setOnLongClickListener { _ -> itemMenu(it); true }
        }
    }
}

class SquareFrame(ctx: Context) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(widthMeasureSpec, widthMeasureSpec)
}
