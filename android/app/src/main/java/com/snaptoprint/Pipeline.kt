package com.snaptoprint

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Detects new photos (scan) and turns them into staged, checksummed queue entries (advance). */
class Pipeline(private val ctx: Context, private val db: Db) {
    private val baselineCache = HashMap<String, Set<Long>>()
    private val stagingDir = File(ctx.filesDir, "staging").also { it.mkdirs() }
    private val maxStageAttempts = 30

    private fun path(): String = db.getKv("source_path") ?: MediaQuery.DEFAULT_PATH

    fun cleanup() {
        stagingDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".tmp")) f.delete()
            else if (f.name.endsWith(".bin")) {
                val p = db.photoById(f.name.removeSuffix(".bin"))
                if (p != null && p.state == "ACKNOWLEDGED") f.delete()
            }
        }
    }

    /** Idempotent: queue rows are written (UNIQUE session+media) before anything else relies on them. */
    fun scan(s: SessionRow) {
        val valid = s.version != null && s.version == MediaQuery.version(ctx)
        val rows = MediaQuery.rows(ctx, path(), if (valid) s.baselineGen else null)
        if (rows.isEmpty()) return
        val known = db.knownMedia(s.id)
        val base = baselineCache.getOrPut(s.id) { db.baselineIds(s.id) }
        val now = System.currentTimeMillis()
        for (r in rows) {
            if (r.id in known || (r.id in base)) continue
            val res = Eligibility.decide(r.id, r.gen, r.taken, base, s.baselineGen, valid, s.startMs, now)
            val trusted = Eligibility.trustedTaken(r.taken, now)
            db.insertPhoto(
                UUID.randomUUID().toString(), s.id, r.id,
                if (res.eligible) "DISCOVERED" else "EXCLUDED",
                r.name, r.mime, r.size, trusted, res.source, now, res.decision.name
            )
        }
    }

    fun advance() {
        for (p in db.photos("state IN ('QUEUED','RETRY_PENDING')")) {
            if (!File(stagingDir, "${p.id}.bin").exists()) db.setPhotoState(p.id, "DISCOVERED", "staged copy missing; re-staging")
        }
        val now = System.currentTimeMillis()
        for (p in db.photos("state IN ('DISCOVERED','WAITING_FOR_FILE')")) {
            val s = db.session(p.sessionId) ?: continue
            try {
                advanceOne(p, s, now)
            } catch (e: SecurityException) {
                db.setKv("error", "PHOTO PERMISSION OFF · open the app and allow photo access")
            }
        }
    }

    private fun advanceOne(p: PhotoRow, s: SessionRow, now: Long) {
        val row = MediaQuery.byId(ctx, p.mediaId)
        if (row == null) {
            if (now - p.discoveredMs > 60_000) db.setPhotoState(p.id, "FAILED_PERMANENTLY", "source image disappeared")
            return
        }
        // Metadata may arrive late: re-check the capture time before staging.
        val trusted = Eligibility.trustedTaken(row.taken, now)
        if (trusted != null && trusted < s.startMs) {
            db.setPhotoState(p.id, "EXCLUDED", "EXCLUDE_BEFORE_START (late metadata)")
            return
        }
        if (trusted != null && p.captureSource != Eligibility.SRC_TAKEN) db.updateCapture(p.id, trusted, Eligibility.SRC_TAKEN)
        // Readiness: size must be unchanged across two consecutive checks.
        if (row.size <= 0 || row.size != p.lastSize) {
            db.setWait(p.id, row.size)
            return
        }
        val free = stagingDir.usableSpace
        if (free < row.size + 64L * 1024 * 1024) {
            db.setKv("error", "STORAGE FULL · free up space on the phone")
            return
        }
        val tmp = File(stagingDir, "${p.id}.tmp")
        val fin = File(stagingDir, "${p.id}.bin")
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            val ins = ctx.contentResolver.openInputStream(MediaQuery.contentUri(p.mediaId)) ?: throw IOException("could not open image")
            ins.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        total += n
                    }
                    out.fd.sync()
                }
            }
        } catch (e: IOException) {
            tmp.delete()
            if (e.message?.contains("ENOSPC") == true) db.setKv("error", "STORAGE FULL · free up space on the phone")
            else db.bumpAttempt(p.id, "read error: ${e.message}", maxStageAttempts)
            return
        }
        if (total != row.size) { // file changed while copying
            tmp.delete()
            db.setWait(p.id, row.size)
            return
        }
        if (!tmp.renameTo(fin)) {
            tmp.delete()
            db.bumpAttempt(p.id, "could not finalize staged copy", maxStageAttempts)
            return
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        db.markQueued(p.id, s.id, total, sha)
        val err = db.getKv("error")
        if (err != null && err.startsWith("STORAGE")) db.setKv("error", null)
    }
}
