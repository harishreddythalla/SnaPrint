package com.snaptoprint

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var db: Db
    private lateinit var prefs: Prefs
    private val h = Handler(Looper.getMainLooper())
    private lateinit var statusTv: TextView
    private lateinit var detailTv: TextView
    private lateinit var pairTv: TextView
    private lateinit var labelEt: EditText
    private lateinit var startBtn: Button
    private lateinit var finishBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var retryBtn: Button
    private lateinit var permBtn: Button
    private lateinit var quitBtn: Button
    private val ticker = object : Runnable {
        override fun run() { refresh(); h.postDelayed(this, 1000) }
    }

    private fun photoPerm() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPerm() = checkSelfPermission(photoPerm()) == PackageManager.PERMISSION_GRANTED

    private fun btn(text: String, size: Float = 18f, onClick: () -> Unit) = Button(this).apply {
        this.text = text; textSize = size; setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db.get(this)
        prefs = Prefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        statusTv = TextView(this).apply { textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); setPadding(28, 28, 28, 28) }
        detailTv = TextView(this).apply { textSize = 16f; setPadding(0, 24, 0, 24) }
        labelEt = EditText(this).apply { hint = "Event name (optional)"; setSingleLine() }
        startBtn = btn("START EVENT", 24f) { onStart() }
        finishBtn = btn("FINISH EVENT", 24f) { onFinish() }
        cancelBtn = btn("CANCEL START") { EventManager.cancelStart(this); refresh() }
        retryBtn = btn("RETRY FAILED PHOTOS") { db.retryFailed(); refresh() }
        permBtn = btn("ALLOW PHOTO ACCESS") { requestPerms() }
        quitBtn = btn("QUIT (stop background service)") { stopService(Intent(this, EventService::class.java)); finishAndRemoveTask() }
        pairTv = TextView(this).apply { textSize = 18f; setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setPadding(0, 24, 0, 8) }
        root.addView(statusTv, mp, wc)
        root.addView(detailTv, mp, wc)
        root.addView(permBtn, mp, wc)
        root.addView(labelEt, mp, wc)
        root.addView(startBtn, mp, 260)
        root.addView(finishBtn, mp, 260)
        root.addView(cancelBtn, mp, wc)
        root.addView(retryBtn, mp, wc)
        root.addView(TextView(this).apply { text = "Pairing code (type this once into the Windows app):"; setPadding(0, 32, 0, 0) }, mp, wc)
        root.addView(pairTv, mp, wc)
        root.addView(btn("NEW PAIRING CODE") { prefs.reset(); refresh() }, mp, wc)
        root.addView(btn("BATTERY SETTINGS (set to Unrestricted)") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }, mp, wc)
        root.addView(quitBtn, mp, wc)
        setContentView(ScrollView(this).apply { addView(root) })
        if (!hasPerm()) requestPerms()
    }

    private fun requestPerms() {
        val perms = ArrayList<String>()
        perms.add(photoPerm())
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ensureService(); refresh()
    }

    private fun ensureService() {
        try { startForegroundService(Intent(this, EventService::class.java)) } catch (e: Exception) {
            Toast.makeText(this, "Could not start background service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        ensureService()
        h.post(ticker)
    }

    override fun onPause() {
        h.removeCallbacks(ticker)
        super.onPause()
    }

    private fun onStart() {
        if (!hasPerm()) { requestPerms(); return }
        if (!Link.connected()) {
            AlertDialog.Builder(this).setTitle("Laptop not connected")
                .setMessage("1. Turn on this phone's Wi-Fi hotspot.\n2. Connect the laptop to it.\n3. Open Snap-to-Print on the laptop and wait for 'CONNECTED'.\n\nThen tap START EVENT again.")
                .setPositiveButton("OK", null).show()
            return
        }
        val label = labelEt.text.toString().trim()
        startBtn.isEnabled = false
        Thread {
            try { EventManager.start(applicationContext, label) } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Could not start: ${e.message}", Toast.LENGTH_LONG).show() }
            }
            runOnUiThread { startBtn.isEnabled = true; ensureService(); refresh() }
        }.start()
    }

    private fun onFinish() {
        val n = db.count("SELECT COUNT(*) FROM photo WHERE state IN $UNDELIVERED")
        val b = AlertDialog.Builder(this)
        if (n == 0L) {
            b.setTitle("Finish event?").setMessage("All photos have been delivered.")
                .setPositiveButton("Finish") { _, _ -> EventManager.requestFinish(this); refresh() }
                .setNegativeButton("Keep event open", null)
        } else {
            b.setTitle("$n photos not yet delivered")
                .setMessage("Finish now and keep sending them in the background, or keep the event open until they arrive.")
                .setPositiveButton("Finish, keep sending") { _, _ -> EventManager.requestFinish(this); refresh() }
                .setNegativeButton("Keep event open", null)
        }
        b.show()
    }

    private fun addresses(): String = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress} (${ni.name})" } }
            .joinToString(", ").ifEmpty { "none" }
    } catch (e: Exception) { "unknown" }

    private fun refresh() {
        val live = db.liveSession()
        val cur = db.currentSession()
        val err = db.getKv("error")
        val conn = Link.connected()
        statusTv.text = Status.summary(db)
        statusTv.setBackgroundColor(
            when {
                err != null -> Color.rgb(176, 32, 32)
                live?.state == "ACTIVE" && conn -> Color.rgb(30, 130, 60)
                live != null -> Color.rgb(200, 120, 0)
                else -> Color.rgb(60, 80, 110)
            }
        )
        var delivered = 0L; var pending = 0L; var failed = 0L; var last = 0L
        if (cur != null) {
            delivered = db.count("SELECT COUNT(*) FROM photo WHERE session_id=? AND state='ACKNOWLEDGED'", cur.id)
            pending = db.count("SELECT COUNT(*) FROM photo WHERE session_id=? AND state IN $UNDELIVERED", cur.id)
            failed = db.count("SELECT COUNT(*) FROM photo WHERE session_id=? AND state='FAILED_PERMANENTLY'", cur.id)
            last = db.count("SELECT COALESCE(MAX(acked_ms),0) FROM photo WHERE session_id=?", cur.id)
        }
        val lastTxt = if (last > 0) SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(last)) else "-"
        val ago = if (Link.lastSeenMs > 0) "${(System.currentTimeMillis() - Link.lastSeenMs) / 1000}s ago" else "never"
        detailTv.text = "Laptop: ${if (conn) "connected" else "not connected"} (last contact $ago)\n" +
            "Event: ${cur?.label ?: "-"}\nDelivered: $delivered   Pending: $pending   Failed: $failed\n" +
            "Last delivered: $lastTxt\nPhone addresses: ${addresses()}"
        pairTv.text = prefs.display()
        permBtn.visibility = if (hasPerm()) View.GONE else View.VISIBLE
        startBtn.visibility = if (live == null) View.VISIBLE else View.GONE
        labelEt.visibility = if (live == null) View.VISIBLE else View.GONE
        finishBtn.visibility = if (live?.state == "ACTIVE") View.VISIBLE else View.GONE
        cancelBtn.visibility = if (live?.state == "STARTING") View.VISIBLE else View.GONE
        retryBtn.visibility = if (db.count("SELECT COUNT(*) FROM photo WHERE state='FAILED_PERMANENTLY'") > 0) View.VISIBLE else View.GONE
        quitBtn.visibility = if (live == null && db.count("SELECT COUNT(*) FROM photo WHERE state IN $UNDELIVERED") == 0L) View.VISIBLE else View.GONE
    }
}
