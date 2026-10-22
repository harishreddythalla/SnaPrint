package com.snaptoprint

import android.content.Context
import java.util.UUID

object EventManager {
    /** Idempotent: a repeated start while an event is live does not create a second session. */
    @Synchronized
    fun start(ctx: Context, label: String) {
        val db = Db.get(ctx)
        if (db.liveSession() != null) return
        val startMs = System.currentTimeMillis()
        val version = MediaQuery.version(ctx)
        val gen = MediaQuery.generation(ctx)
        // Baseline = everything with generation <= gen. A photo saved after gen can never be in it.
        val ids = MediaQuery.idsUpToGeneration(ctx, db.getKv("source_path") ?: MediaQuery.DEFAULT_PATH, gen)
        db.createSession(UUID.randomUUID().toString(), label.ifBlank { "Event" }, startMs, gen, version, ids)
    }

    fun requestFinish(ctx: Context) {
        val db = Db.get(ctx)
        db.liveSession()?.let { db.beginFinishing(it.id) }
    }

    fun cancelStart(ctx: Context) {
        val db = Db.get(ctx)
        db.liveSession()?.let { db.cancelStarting(it.id) }
    }
}
