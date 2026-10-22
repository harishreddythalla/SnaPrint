package com.snaptoprint

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Foreground service (type dataSync): hosts the local server and runs the detect/stage loop. */
class EventService : Service() {
    private lateinit var db: Db
    private lateinit var pipeline: Pipeline
    private var server: LocalServer? = null
    private var exec: ScheduledExecutorService? = null
    private var observer: ContentObserver? = null
    private var wake: PowerManager.WakeLock? = null
    private var lastNotif = 0L

    companion object {
        const val CHANNEL = "event"
        const val NOTIF_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        db = Db.get(this)
        pipeline = Pipeline(this, db)
        pipeline.cleanup()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Event status", NotificationManager.IMPORTANCE_LOW))
        try {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            Log.e("EventService", "cannot start foreground", e)
            db.setKv("error", "BACKGROUND SERVICE BLOCKED · open the app and try again")
            stopSelf()
            return START_NOT_STICKY
        }
        if (server == null) startServer()
        if (exec == null) startLoop()
        return START_STICKY
    }

    private fun startServer() {
        try {
            val srv = LocalServer(this, db, Prefs(this))
            srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = srv
        } catch (e: IOException) {
            db.setKv("error", "CANNOT OPEN PORT ${Prefs.PORT} · close other apps and restart")
        }
    }

    private fun startLoop() {
        val ex = Executors.newSingleThreadScheduledExecutor()
        exec = ex
        ex.scheduleWithFixedDelay({ tick() }, 0L, 500L, TimeUnit.MILLISECONDS)
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                try { ex.execute { tick() } } catch (e: Exception) { /* shutting down */ }
            }
        }
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs)
        observer = obs
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "snaptoprint:event").also { it.acquire() }
    }

    private fun tick() {
        try {
            val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            val granted = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
            val err = db.getKv("error")
            if (!granted) db.setKv("error", "PHOTO PERMISSION OFF · open the app and allow photo access")
            else if (err != null && err.startsWith("PHOTO PERMISSION")) db.setKv("error", null)
            if (err != null && err.startsWith("STORAGE") && filesDir.usableSpace > 200L * 1024 * 1024) db.setKv("error", null)
            if (granted) {
                for (s in db.sessions("state IN ('STARTING','ACTIVE','FINISHING')")) pipeline.scan(s)
                pipeline.advance()
            }
            transitions()
            val now = System.currentTimeMillis()
            if (now - lastNotif > 2000) {
                lastNotif = now
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())
            }
        } catch (t: Throwable) {
            Log.e("EventService", "tick failed", t)
        }
    }

    private fun transitions() {
        val now = System.currentTimeMillis()
        for (s in db.sessions("state='FINISHING'")) {
            val settling = db.count("SELECT COUNT(*) FROM photo WHERE session_id=? AND state IN ('DISCOVERED','WAITING_FOR_FILE')", s.id)
            if (now - s.finishingMs >= 3000 && settling == 0L) {
                val undelivered = db.count("SELECT COUNT(*) FROM photo WHERE session_id=? AND state IN $UNDELIVERED", s.id)
                db.setSessionState(s.id, if (undelivered == 0L) "FINISHED" else "FINISHED_WITH_PENDING")
            }
        }
        for (s in db.sessions("state='FINISHED_WITH_PENDING'")) {
            if (db.count("SELECT COUNT(*) FROM photo WHERE session_id=? AND state IN $UNDELIVERED", s.id) == 0L)
                db.setSessionState(s.id, "FINISHED")
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Snap-to-Print")
            .setContentText(Status.summary(db))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        server?.stop()
        exec?.shutdownNow()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
