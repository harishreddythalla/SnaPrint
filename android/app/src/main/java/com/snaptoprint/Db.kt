package com.snaptoprint

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SessionRow(
    val id: String, val label: String, val startMs: Long, val state: String,
    val baselineGen: Long, val version: String?, val readyAck: Boolean,
    val nextSeq: Int, val finishingMs: Long
)

data class PhotoRow(
    val id: String, val sessionId: String, val mediaId: Long, val seq: Int, val state: String,
    val displayName: String?, val mime: String?, val size: Long, val capturedMs: Long?,
    val captureSource: String, val sha256: String?, val lastSize: Long, val attempts: Int,
    val discoveredMs: Long
)

const val UNDELIVERED = "('DISCOVERED','WAITING_FOR_FILE','QUEUED','RETRY_PENDING')"

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "snaptoprint.db", null, 1) {
    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }
        private const val SCOLS = "id,label,start_ms,state,baseline_gen,mediastore_version,ready_ack,next_seq,finishing_ms"
        private const val PCOLS = "id,session_id,media_id,seq,state,display_name,mime,size,captured_ms,capture_source,sha256,last_size,attempts,discovered_ms"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE session(
            id TEXT PRIMARY KEY, label TEXT NOT NULL, start_ms INTEGER NOT NULL, state TEXT NOT NULL,
            baseline_gen INTEGER NOT NULL, mediastore_version TEXT, ready_ack INTEGER NOT NULL DEFAULT 0,
            next_seq INTEGER NOT NULL DEFAULT 1, finishing_ms INTEGER NOT NULL DEFAULT 0, created_ms INTEGER NOT NULL)""")
        db.execSQL("CREATE TABLE baseline(session_id TEXT NOT NULL, media_id INTEGER NOT NULL, PRIMARY KEY(session_id, media_id))")
        db.execSQL("""CREATE TABLE photo(
            id TEXT PRIMARY KEY, session_id TEXT NOT NULL, media_id INTEGER NOT NULL, seq INTEGER, state TEXT NOT NULL,
            display_name TEXT, mime TEXT, size INTEGER NOT NULL DEFAULT 0, captured_ms INTEGER,
            capture_source TEXT NOT NULL, sha256 TEXT, last_size INTEGER NOT NULL DEFAULT -1,
            attempts INTEGER NOT NULL DEFAULT 0, next_retry_ms INTEGER NOT NULL DEFAULT 0, error TEXT,
            discovered_ms INTEGER NOT NULL, acked_ms INTEGER, UNIQUE(session_id, media_id))""")
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 only. Future migrations must be additive (ALTER TABLE ADD COLUMN); never drop user data.
    }

    // ---- helpers
    fun count(sql: String, vararg args: String): Long =
        DatabaseUtils.longForQuery(readableDatabase, sql, arrayOf(*args))

    fun getKv(k: String): String? =
        readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setKv(k: String, v: String?) {
        if (v == null) writableDatabase.execSQL("DELETE FROM kv WHERE k=?", arrayOf<Any?>(k))
        else writableDatabase.execSQL("INSERT OR REPLACE INTO kv(k,v) VALUES(?,?)", arrayOf<Any?>(k, v))
    }

    // ---- sessions
    private fun sess(c: Cursor) = SessionRow(
        c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getLong(4),
        if (c.isNull(5)) null else c.getString(5), c.getInt(6) == 1, c.getInt(7), c.getLong(8)
    )

    fun sessions(where: String, vararg args: String): List<SessionRow> {
        val out = ArrayList<SessionRow>()
        readableDatabase.rawQuery("SELECT $SCOLS FROM session WHERE $where ORDER BY created_ms DESC", arrayOf(*args)).use {
            while (it.moveToNext()) out.add(sess(it))
        }
        return out
    }

    fun session(id: String): SessionRow? = sessions("id=?", id).firstOrNull()
    fun liveSession(): SessionRow? = sessions("state IN ('STARTING','ACTIVE','FINISHING')").firstOrNull()
    fun currentSession(): SessionRow? = liveSession() ?: sessions("1=1").firstOrNull()

    fun createSession(id: String, label: String, startMs: Long, gen: Long, version: String?, baseline: List<Long>) {
        val d = writableDatabase
        d.beginTransaction()
        try {
            d.execSQL(
                "INSERT INTO session(id,label,start_ms,state,baseline_gen,mediastore_version,created_ms) VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any?>(id, label, startMs, "STARTING", gen, version, System.currentTimeMillis())
            )
            val st = d.compileStatement("INSERT OR IGNORE INTO baseline(session_id,media_id) VALUES(?,?)")
            for (m in baseline) {
                st.bindString(1, id); st.bindLong(2, m); st.executeInsert()
            }
            st.close()
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    fun baselineIds(sessionId: String): Set<Long> {
        val out = HashSet<Long>()
        readableDatabase.rawQuery("SELECT media_id FROM baseline WHERE session_id=?", arrayOf(sessionId)).use {
            while (it.moveToNext()) out.add(it.getLong(0))
        }
        return out
    }

    fun setSessionState(id: String, state: String) =
        writableDatabase.execSQL("UPDATE session SET state=? WHERE id=?", arrayOf<Any?>(state, id))

    fun setReady(id: String) = writableDatabase.execSQL(
        "UPDATE session SET ready_ack=1, state=CASE WHEN state='STARTING' THEN 'ACTIVE' ELSE state END WHERE id=?", arrayOf<Any?>(id))

    fun beginFinishing(id: String) = writableDatabase.execSQL(
        "UPDATE session SET state='FINISHING', finishing_ms=? WHERE id=? AND state='ACTIVE'", arrayOf<Any?>(System.currentTimeMillis(), id))

    fun cancelStarting(id: String) = writableDatabase.execSQL(
        "UPDATE session SET state='CANCELLED' WHERE id=? AND state='STARTING'", arrayOf<Any?>(id))

    // ---- photos
    private fun photo(c: Cursor) = PhotoRow(
        c.getString(0), c.getString(1), c.getLong(2), c.getInt(3), c.getString(4), c.getString(5), c.getString(6),
        c.getLong(7), if (c.isNull(8)) null else c.getLong(8), c.getString(9), c.getString(10), c.getLong(11),
        c.getInt(12), c.getLong(13)
    )

    fun photos(where: String, vararg args: String): List<PhotoRow> {
        val out = ArrayList<PhotoRow>()
        readableDatabase.rawQuery("SELECT $PCOLS FROM photo WHERE $where ORDER BY discovered_ms ASC", arrayOf(*args)).use {
            while (it.moveToNext()) out.add(photo(it))
        }
        return out
    }

    fun photoById(id: String): PhotoRow? = photos("id=?", id).firstOrNull()

    fun knownMedia(sessionId: String): Set<Long> {
        val out = HashSet<Long>()
        readableDatabase.rawQuery("SELECT media_id FROM photo WHERE session_id=?", arrayOf(sessionId)).use {
            while (it.moveToNext()) out.add(it.getLong(0))
        }
        return out
    }

    /** INSERT OR IGNORE on UNIQUE(session_id, media_id): repeated notifications cannot create duplicates. */
    fun insertPhoto(
        id: String, sessionId: String, mediaId: Long, state: String, name: String?, mime: String?, size: Long,
        capturedMs: Long?, source: String, now: Long, note: String?
    ) {
        val cv = ContentValues()
        cv.put("id", id); cv.put("session_id", sessionId); cv.put("media_id", mediaId); cv.put("state", state)
        cv.put("display_name", name); cv.put("mime", mime); cv.put("size", size); cv.put("captured_ms", capturedMs)
        cv.put("capture_source", source); cv.put("last_size", size); cv.put("discovered_ms", now); cv.put("error", note)
        writableDatabase.insertWithOnConflict("photo", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun setWait(id: String, lastSize: Long) = writableDatabase.execSQL(
        "UPDATE photo SET state='WAITING_FOR_FILE', last_size=? WHERE id=?", arrayOf<Any?>(lastSize, id))

    fun setPhotoState(id: String, state: String, note: String?) = writableDatabase.execSQL(
        "UPDATE photo SET state=?, error=? WHERE id=?", arrayOf<Any?>(state, note, id))

    fun bumpAttempt(id: String, note: String, maxAttempts: Int) {
        writableDatabase.execSQL("UPDATE photo SET attempts=attempts+1, error=? WHERE id=?", arrayOf<Any?>(note, id))
        writableDatabase.execSQL("UPDATE photo SET state='FAILED_PERMANENTLY' WHERE id=? AND attempts>=?", arrayOf<Any?>(id, maxAttempts))
    }

    fun updateCapture(id: String, capturedMs: Long?, source: String) = writableDatabase.execSQL(
        "UPDATE photo SET captured_ms=?, capture_source=? WHERE id=?", arrayOf<Any?>(capturedMs, source, id))

    /** Atomically: assign sequence number, store checksum, and move to QUEUED. */
    fun markQueued(photoId: String, sessionId: String, size: Long, sha: String) {
        val d = writableDatabase
        d.beginTransaction()
        try {
            val seq = DatabaseUtils.longForQuery(d, "SELECT next_seq FROM session WHERE id=?", arrayOf(sessionId))
            d.execSQL("UPDATE photo SET state='QUEUED', seq=?, size=?, sha256=?, error=NULL WHERE id=?",
                arrayOf<Any?>(seq, size, sha, photoId))
            d.execSQL("UPDATE session SET next_seq=next_seq+1 WHERE id=?", arrayOf<Any?>(sessionId))
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    fun nextForTransfer(now: Long): Pair<PhotoRow, SessionRow>? {
        val id = readableDatabase.rawQuery(
            "SELECT p.id FROM photo p JOIN session s ON s.id=p.session_id " +
                "WHERE p.state IN ('QUEUED','RETRY_PENDING') AND p.next_retry_ms<=? ORDER BY s.created_ms ASC, p.seq ASC LIMIT 1",
            arrayOf(now.toString())
        ).use { if (it.moveToFirst()) it.getString(0) else null } ?: return null
        val p = photoById(id) ?: return null
        val s = session(p.sessionId) ?: return null
        return Pair(p, s)
    }

    fun ack(id: String) = writableDatabase.execSQL(
        "UPDATE photo SET state='ACKNOWLEDGED', acked_ms=?, error=NULL WHERE id=?", arrayOf<Any?>(System.currentTimeMillis(), id))

    fun nack(id: String, retry: Boolean, reason: String, maxAttempts: Int) {
        val p = photoById(id) ?: return
        if (p.state == "ACKNOWLEDGED") return
        val n = p.attempts + 1
        if (!retry || n >= maxAttempts) {
            writableDatabase.execSQL("UPDATE photo SET state='FAILED_PERMANENTLY', attempts=?, error=? WHERE id=?",
                arrayOf<Any?>(n, reason, id))
        } else {
            writableDatabase.execSQL("UPDATE photo SET state='RETRY_PENDING', attempts=?, error=?, next_retry_ms=? WHERE id=?",
                arrayOf<Any?>(n, reason, System.currentTimeMillis() + Backoff.delayMs(n), id))
        }
    }

    fun retryFailed() = writableDatabase.execSQL(
        "UPDATE photo SET state='RETRY_PENDING', attempts=0, next_retry_ms=0 WHERE state='FAILED_PERMANENTLY'")
}
