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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
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
    private var pendingOut: Triple<List<String>?, Boolean, Boolean>? = null   // ids (null = all), move, hide
    private var pendingAfterFolder: (() -> Unit)? = null
    private var pendingShared: List<Uri>? = null
    private lateinit var selBar: LinearLayout
    private lateinit var selText: TextView
    private val selected = LinkedHashSet<String>()
    private var selecting = false

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
    private val pickOutFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val o = pendingOut
        pendingOut = null
        if (uri != null && o != null) {
            endSelection()
            run(if (o.second) "Moving photos out" else "Copying photos out") { Tools.export(this, uri, o.first, o.second, o.third, it) }
        }
    }
    /** Picks (or creates) the Lifeboat folder, where photos are kept as normal visible files. */
    private class OpenTreeAt : ActivityResultContracts.OpenDocumentTree() {
        override fun createIntent(context: Context, input: Uri?): Intent = super.createIntent(context, input).putExtra(
            android.provider.DocumentsContract.EXTRA_INITIAL_URI,
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3A")
        )
    }
    private val pickLifeboatFolder = registerForActivityResult(OpenTreeAt()) { uri ->
        val next = pendingAfterFolder
        pendingAfterFolder = null
        if (uri == null) return@registerForActivityResult
        try {
            val st = PlainStore.set(this, uri)
            Toast.makeText(this, "Lifeboat folder: ${st.label()}", Toast.LENGTH_LONG).show()
            refresh()
            next?.invoke()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't use that folder: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    /** Opens Google Photos itself so you can pick photos (cloud ones too). Google Photos downloads them for us. */
    private class PickFromGoogle : ActivityResultContracts.GetMultipleContents() {
        override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input)
            .setPackage("com.google.android.apps.photos")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
    }
    private val pickGoogle = registerForActivityResult(PickFromGoogle()) { uris ->
        if (uris.isNotEmpty()) run("Saving ${uris.size} from Google Photos") { Importer.importUris(this, uris, it, "google") }
    }
    private val pickVisual = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        if (uris.isNotEmpty()) run("Saving ${uris.size} photos/videos") { Importer.importUris(this, uris, it, "google") }
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
        Vault.mode(this) // decide plain vs encrypted before the vault is first opened
        setContentView(buildUi())
        onBackPressedDispatcher.addCallback(this) {
            if (selecting) endSelection() else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
        }
        takeShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShared(intent)
    }

    /** "Share → Lifeboat" from Google Photos, the gallery or Files. */
    private fun takeShared(i: Intent?) {
        i ?: return
        val uris = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri))
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) pendingShared = uris
        i.action = null
    }

    private fun runShared() {
        val uris = pendingShared ?: return
        pendingShared = null
        requireFolder { run("Saving ${uris.size} shared photos/videos") { Importer.importUris(this, uris, it, "shared") } }
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
        if (pendingShared != null) runShared() else offerVisible()
    }

    /** v0.2 vaults were encrypted and hidden. Offer once to turn them into normal files you can see. */
    private fun offerVisible() {
        val prefs = getSharedPreferences("lifeboat", Context.MODE_PRIVATE)
        if (prefs.getBoolean("askedVisible", false)) return
        val n = try { Vault.get(this).encryptedCount() } catch (e: Exception) { 0 }
        if (n == 0) return
        prefs.edit().putBoolean("askedVisible", true).apply()
        AlertDialog.Builder(this).setTitle("Make your photos normal files?")
            .setMessage("New in this version: Lifeboat can keep your photos as normal files in a folder you pick on the phone or SD card. " +
                "You can see them in the Files app and copy them straight to your laptop.\n\n" +
                "Google Photos and gallery apps still can't see them, and Lifeboat still has no internet.\n\n" +
                "This converts all $n photos/videos. Each copy is checked before the hidden one is removed.")
            .setPositiveButton("Yes, make them visible") { _, _ -> convertVisible() }
            .setNegativeButton("Not now", null).show()
    }

    private fun convertVisible() {
        Vault.setMode(this, "plain")
        requireFolder { run("Making your photos visible") { Vault.get(this).convertToPlain(it) } }
    }

    /** Runs [then] once a Lifeboat folder is set (only needed for normal visible files). */
    private fun requireFolder(then: () -> Unit) {
        if (Vault.mode(this) == "enc" || PlainStore.current(this) != null) { then(); return }
        AlertDialog.Builder(this).setTitle("Pick your Lifeboat folder")
            .setMessage("Your photos will be kept here as normal files you can see and copy to your laptop.\n\n" +
                "On the next screen:\n1️⃣  Choose the phone or your SD card (☰ menu, top left).\n" +
                "2️⃣  Tap \"Create new folder\" and name it Lifeboat.\n3️⃣  Tap \"Use this folder\", then Allow.\n\n" +
                "Lifeboat adds a hidden .nomedia file so Google Photos and gallery apps leave it alone. Don't use DCIM or Pictures.")
            .setPositiveButton("Choose folder") { _, _ -> pendingAfterFolder = then; pickLifeboatFolder.launch(null) }
            .setNegativeButton("Cancel", null).show()
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
            text = "🧱 No internet · Google Photos can't see it · Your photos stay yours"
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
        row.addView(small("＋ Add") { requireFolder { addMenu() } }, lp(1f))
        row.addView(small("⬇ Get out") { getOut() }, lp(1f))
        row.addView(small("⋮ More") { moreMenu() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(row)

        selBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        selText = TextView(this).apply { textSize = 15f; setTypeface(typeface, Typeface.BOLD) }
        fun flat(label: String, click: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = label; isAllCaps = false; textSize = 14f; setPadding(6.px(), 0, 6.px(), 0); minWidth = 0; minimumWidth = 0; setOnClickListener { click() }
        }
        selBar.addView(selText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        selBar.addView(flat("All") { selectAll() })
        selBar.addView(flat("Cancel") { endSelection() })
        selBar.addView(MaterialButton(this).apply {
            text = "Next ➜"; isAllCaps = false; setOnClickListener {
                if (selected.isEmpty()) Toast.makeText(this@MainActivity, "Tap photos to select them", Toast.LENGTH_SHORT).show()
                else outChoice(selected.toList())
            }
        })
        col.addView(selBar)

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
            text = "Lifeboat is empty.\n\nTap \"＋ Add\" to bring in photos from your phone, SD card or Google Photos, " +
                "or \"Rescue my Google Photos\" to pull out your whole library.\n\nOnce a photo is in here, nothing Google, Motorola " +
                "or any trash can does can touch it. Tap \"⬇ Get out\" any time to copy them anywhere."
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
        val where = if (Vault.mode(this) == "plain") (PlainStore.current(this)?.label() ?: "no folder picked yet")
            else if (Vault.location(this) == "sd") "encrypted, on the SD card" else "encrypted, on the phone"
        stats.text = "${items.size} photos & videos · ${humanBytes(v.totalBytes())} · $where"
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
                "Lifeboat pulls every photo and video straight out of the zips, checks each one, fixes the dates and puts them behind the wall.\n\n" +
                "Only need some photos? Use ＋ Add → From Google Photos instead."
            )
            .setPositiveButton("Open Google export") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://takeout.google.com/settings/takeout/custom/photos")))
                } catch (e: Exception) {
                    Toast.makeText(this, "Open takeout.google.com in your browser", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Bring them in") { _, _ -> requireFolder { pickZips.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) } }
            .show()
    }

    private fun addMenu() {
        val opts = arrayOf("📱  From the phone", "💾  From the SD card", "☁️  From Google Photos (pick which ones)", "📦  ALL of Google Photos (Takeout)")
        AlertDialog.Builder(this).setTitle("Add to Lifeboat").setItems(opts) { _, w ->
            when (w) {
                0 -> pickOrFolder("From the phone", "e.g. DCIM › Camera")
                1 -> pickOrFolder("From the SD card", "e.g. DCIM on the SD card. Open it from the ☰ menu")
                2 -> fromGoogle()
                3 -> rescueGuide()
            }
        }.show()
    }

    private fun pickOrFolder(title: String, hint: String) {
        AlertDialog.Builder(this).setTitle(title).setItems(arrayOf("Pick photos & videos", "A whole folder ($hint)")) { _, w ->
            if (w == 0) pickMedia.launch(arrayOf("image/*", "video/*")) else pickFolder.launch(null)
        }.show()
    }

    private fun fromGoogle() {
        AlertDialog.Builder(this).setTitle("From Google Photos")
            .setMessage("Google Photos opens. Select the photos you want (long-press one, then tap the rest) and tap Done. " +
                "They get downloaded straight into Lifeboat, out of Google's reach.\n\n" +
                "Another way: in Google Photos, select photos → Share → Lifeboat.\n\n" +
                "Afterwards you can delete them from Google Photos yourself. Not sure? Use ⋮ More → \"Safe to delete?\" first.")
            .setPositiveButton("Open Google Photos") { _, _ ->
                try { pickGoogle.launch("*/*") } catch (e: android.content.ActivityNotFoundException) {
                    pickVisual.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- getting photos out ----------
    private fun getOut() {
        val n = try { Vault.get(this).count() } catch (e: Exception) { 0 }
        if (n == 0) { Toast.makeText(this, "Nothing in Lifeboat yet", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Get photos out").setItems(arrayOf("Choose photos", "All $n photos & videos")) { _, w ->
            if (w == 0) startSelection(null) else outChoice(null)
        }.show()
    }

    /** Copy (keep in Lifeboat too) or move (remove Lifeboat's copy after checking) to any folder on the phone or SD card. */
    private fun outChoice(ids: List<String>?) {
        val count = ids?.size ?: Vault.get(this).count()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24.px(), 8.px(), 24.px(), 0) }
        box.addView(TextView(this).apply {
            textSize = 14f
            text = "Next, pick or create a folder on the phone or SD card (☰ menu, top left). Photos are saved there as normal files, sorted by year.\n\n" +
                "Copy = they stay in Lifeboat too.\nMove = Lifeboat's copy is removed after the new one is checked.\n\n" +
                "Deleting from Lifeboat never deletes copies you saved somewhere else."
        })
        val hide = CheckBox(this).apply { text = "Hide them from Google Photos & gallery apps"; isChecked = true }
        box.addView(hide)
        AlertDialog.Builder(this).setTitle("$count photos & videos").setView(box)
            .setPositiveButton("Copy") { _, _ -> pendingOut = Triple(ids, false, hide.isChecked); pickOutFolder.launch(null) }
            .setNeutralButton("Move") { _, _ -> pendingOut = Triple(ids, true, hide.isChecked); pickOutFolder.launch(null) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun startSelection(first: String?) {
        selecting = true
        selected.clear(); first?.let { selected += it }
        selBar.visibility = View.VISIBLE
        updateSel()
        if (first == null) Toast.makeText(this, "Tap photos to select them", Toast.LENGTH_SHORT).show()
    }

    private fun endSelection() {
        selecting = false; selected.clear(); selBar.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun selectAll() {
        val all = Vault.get(this).list().map { it.id }
        if (selected.size == all.size) selected.clear() else { selected.clear(); selected += all }
        updateSel()
    }

    private fun toggle(id: String) { if (!selected.remove(id)) selected += id; updateSel() }

    private fun updateSel() { selText.text = "${selected.size} selected"; adapter.notifyDataSetChanged() }

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
                "Lifeboat only READS the card and never writes to it. Everything it finds is checked and goes straight into Lifeboat. " +
                "Big cards take a while (roughly 1–3 min per GB). Keep the phone plugged in."
            )
            .setPositiveButton("Scan the card") { _, _ -> requireFolder { pickUsbDevice() } }
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
            .setMessage("Pick the photos you want to delete from Google Photos or your phone. Lifeboat checks each one, byte for byte, against what it has and tells you which ones are safe to delete and which aren't.")
            .setPositiveButton("Pick photos") { _, _ -> pickCheck.launch(arrayOf("image/*", "video/*")) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun moreMenu() {
        val v = try { Vault.get(this) } catch (e: Exception) { null }
        val encN = v?.encryptedCount() ?: 0
        val sd = Vault.sdRoot(this) != null
        val onSd = Vault.location(this) == "sd"
        val folder = PlainStore.current(this)
        val opts = mutableListOf(
            "🛟  Recover DELETED photos from an SD card",
            "✅  Safe to delete from Google?",
            "📁  Lifeboat folder: ${folder?.label() ?: "not picked"}",
        )
        if (encN > 0) opts += "👁  Make $encN hidden photos visible files"
        opts += listOf("🔐  Back up everything (password-locked file)", "♻️  Restore a backup", "🔎  Check every file")
        if (encN > 0) { if (onSd) opts += "Move hidden vault to the phone" else if (sd) opts += "Move hidden vault to the SD card" }
        opts += "About the wall"
        AlertDialog.Builder(this).setItems(opts.toTypedArray()) { _, w ->
            val o = opts[w]
            when {
                o.startsWith("🛟") -> recoverGuide()
                o.startsWith("✅") -> safeToDelete()
                o.startsWith("📁") -> changeFolder()
                o.startsWith("👁") -> convertVisible()
                o.startsWith("🔐") -> askPassword("Choose a backup password", confirm = true) { pw ->
                    pendingBackupPassword = pw
                    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    createBackup.launch("lifeboat-backup-$stamp.lifeboat")
                }
                o.startsWith("♻️") -> requireFolder { openBackup.launch(arrayOf("*/*")) }
                o.startsWith("🔎") -> run("Checking every file") { Tools.verifyAll(this, it) }
                o == "Move hidden vault to the phone" -> confirmMove("phone")
                o == "Move hidden vault to the SD card" -> confirmMove("sd")
                else -> aboutWall()
            }
        }.show()
    }

    private fun changeFolder() {
        val cur = PlainStore.current(this)
        AlertDialog.Builder(this).setTitle("Lifeboat folder")
            .setMessage((if (cur != null) "Now: ${cur.label()}\n\n" else "") +
                "New photos are saved to this folder as normal files, sorted into year and month folders. You can open it in the Files app or copy it to your laptop over USB.\n\n" +
                "Changing it only affects new photos. The ones already saved stay where they are.")
            .setPositiveButton("Choose folder") { _, _ -> pendingAfterFolder = null; pickLifeboatFolder.launch(null) }
            .setNegativeButton("Close", null).show()
    }

    private fun confirmMove(to: String) {
        AlertDialog.Builder(this)
            .setTitle(if (to == "sd") "Move to SD card?" else "Move to phone?")
            .setMessage("Everything is copied and checked first. The old copy is deleted only after that.\n\nNote: Android deletes the hidden vault, on the SD card too, if you uninstall the app. Make your photos visible files or keep a backup.")
            .setPositiveButton("Move") { _, _ -> run("Moving the vault") { Tools.move(this, to, it) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun aboutWall() {
        AlertDialog.Builder(this).setTitle("The wall").setMessage(
            "• No internet permission. Lifeboat physically can't upload or sync anything. Android blocks it.\n\n" +
            "• Your files, visible to you. Photos are normal files in your Lifeboat folder. Open it in the Files app or copy it to a laptop.\n\n" +
            "• Invisible to Google. A .nomedia file in every folder keeps Google Photos, Motorola's gallery and the media scanner out, so nothing gets backed up to Google.\n\n" +
            "• Verified copies. Every photo is copied, read back and compared to the original's fingerprint (SHA-256) before it counts as saved.\n\n" +
            "• Trash-proof. Deleting or emptying a trash in Google Photos or Gallery can't touch what's in here.\n\n" +
            "• Deleting in Lifeboat only deletes Lifeboat's copy. Copies you saved elsewhere stay.\n\n" +
            "• Locked. Uses your phone's screen lock or fingerprint. Screenshots are blocked.\n\n" +
            "Tip: don't rename or move files inside the Lifeboat folder yourself, or Lifeboat loses track of them. Use ⬇ Get out to copy them anywhere."
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
        // NOTE: setMessage() and setItems() can't be combined (message hides the list), so info goes in the title.
        AlertDialog.Builder(this)
            .setTitle("${item.name}\n${formatDate(item.taken)} · ${humanBytes(item.size)}")
            .setItems(arrayOf("Select more…", "Copy or move out", "Delete from Lifeboat")) { _, w ->
                when (w) {
                    0 -> startSelection(item.id)
                    1 -> outChoice(listOf(item.id))
                    2 -> AlertDialog.Builder(this).setTitle("Delete from Lifeboat?")
                        .setMessage("This permanently deletes Lifeboat's copy. Copies you saved somewhere else are not touched. There's no trash can here, on purpose.")
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

        inner class VH(val root: SquareFrame, val img: ImageView, val badge: TextView, val check: TextView) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val f = SquareFrame(parent.context).apply { setPadding(2, 2, 2, 2) }
            val img = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0x22888888) }
            val badge = TextView(parent.context).apply {
                text = "▶"; setTextColor(Color.WHITE); textSize = 14f; setShadowLayer(4f, 0f, 0f, Color.BLACK); setPadding(8, 4, 8, 4)
            }
            f.addView(img, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            f.addView(badge, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
            val check = TextView(parent.context).apply {
                text = "✓"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
                setBackgroundColor(0xFF1E88E5.toInt()); visibility = View.GONE
            }
            f.addView(check, FrameLayout.LayoutParams(28.px(), 28.px(), Gravity.TOP or Gravity.END))
            return VH(f, img, badge, check)
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
            val sel = selecting && it.id in selected
            h.check.visibility = if (sel) View.VISIBLE else View.GONE
            h.img.alpha = if (selecting && !sel) 0.55f else 1f
            h.root.setOnClickListener { _ ->
                if (selecting) toggle(it.id)
                else startActivity(Intent(this@MainActivity, ViewerActivity::class.java).putExtra("id", it.id))
            }
            h.root.setOnLongClickListener { _ -> if (selecting) toggle(it.id) else itemMenu(it); true }
        }
    }
}

class SquareFrame(ctx: Context) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(widthMeasureSpec, widthMeasureSpec)
}
