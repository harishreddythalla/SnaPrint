package com.snaptoprint

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Protocol v1 server. See protocol/protocol-v1.md. */
class LocalServer(private val ctx: Context, private val db: Db, private val prefs: Prefs) : NanoHTTPD(Prefs.PORT) {
    private val maxAttempts = 8

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        Log.e("LocalServer", "request failed", e)
        err(Response.Status.INTERNAL_ERROR, "internal")
    }

    private fun json(st: Response.Status, o: JSONObject): Response =
        NanoHTTPD.newFixedLengthResponse(st, "application/json", o.toString())

    private fun err(st: Response.Status, code: String): Response = json(st, JSONObject().put("error", code))

    private fun authorized(s: IHTTPSession): Boolean {
        val h = s.headers["authorization"] ?: return false
        return MessageDigest.isEqual(h.toByteArray(), ("Bearer " + prefs.token()).toByteArray())
    }

    private fun body(s: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        s.parseBody(files)
        return try { JSONObject(files["postData"] ?: "{}") } catch (e: Exception) { JSONObject() }
    }

    private fun route(s: IHTTPSession): Response {
        val uri = s.uri.trimEnd('/')
        val m = s.method
        if (m == Method.GET && uri == "/api/v1/health")
            return json(Response.Status.OK, JSONObject().put("app", "snap-to-print").put("protocol", 1).put("authorized", authorized(s)))
        if (!authorized(s)) return err(Response.Status.UNAUTHORIZED, "unauthorized")
        Link.lastSeenMs = System.currentTimeMillis()
        val p = uri.removePrefix("/api/v1/").split("/")
        return when {
            m == Method.GET && uri == "/api/v1/session/current" -> sessionCurrent()
            m == Method.GET && uri == "/api/v1/queue/next" -> queueNext()
            m == Method.POST && p.size == 3 && p[0] == "session" && p[2] == "ready" -> ready(p[1])
            m == Method.POST && p.size == 3 && p[0] == "session" && p[2] == "finish-ack" -> finishAck(p[1])
            m == Method.GET && p.size == 3 && p[0] == "photos" && p[2] == "content" -> content(p[1])
            m == Method.POST && p.size == 3 && p[0] == "photos" && p[2] == "ack" -> ack(p[1], body(s))
            m == Method.POST && p.size == 3 && p[0] == "photos" && p[2] == "nack" -> nack(p[1], body(s))
            else -> err(Response.Status.NOT_FOUND, "not_found")
        }
    }

    private fun sessionCurrent(): Response {
        val cur = db.currentSession() ?: return json(Response.Status.OK, JSONObject().put("state", "NONE"))
        return json(
            Response.Status.OK,
            JSONObject().put("state", cur.state).put("sessionId", cur.id).put("label", cur.label)
                .put("startMs", cur.startMs).put("readyAck", cur.readyAck)
        )
    }

    private fun ready(id: String): Response {
        val s = db.session(id) ?: return err(Response.Status.NOT_FOUND, "no_such_session")
        if (s.state == "CANCELLED") return err(Response.Status.CONFLICT, "cancelled")
        db.setReady(id) // durable before we answer
        return json(Response.Status.OK, JSONObject().put("state", db.session(id)?.state ?: s.state))
    }

    private fun finishAck(id: String): Response {
        if (db.session(id) == null) return err(Response.Status.NOT_FOUND, "no_such_session")
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun queueNext(): Response {
        val o = JSONObject()
            .put("pending", db.count("SELECT COUNT(*) FROM photo WHERE state IN $UNDELIVERED"))
            .put("failed", db.count("SELECT COUNT(*) FROM photo WHERE state='FAILED_PERMANENTLY'"))
        val next = db.nextForTransfer(System.currentTimeMillis())
        if (next == null) {
            o.put("item", JSONObject.NULL)
        } else {
            val (p, s) = next
            o.put(
                "item",
                JSONObject().put("photoId", p.id).put("sessionId", s.id).put("sessionLabel", s.label)
                    .put("sessionStartMs", s.startMs).put("seq", p.seq).put("fileName", p.displayName ?: "photo.jpg")
                    .put("mime", p.mime ?: JSONObject.NULL).put("size", p.size)
                    .put("capturedMs", p.capturedMs ?: JSONObject.NULL).put("captureSource", p.captureSource)
                    .put("sha256", p.sha256 ?: "")
            )
        }
        return json(Response.Status.OK, o)
    }

    private fun content(id: String): Response {
        val p = db.photoById(id) ?: return err(Response.Status.NOT_FOUND, "no_such_photo")
        if (p.state == "ACKNOWLEDGED") return err(Response.Status.GONE, "already_acknowledged")
        if (p.state != "QUEUED" && p.state != "RETRY_PENDING") return err(Response.Status.CONFLICT, "not_ready")
        val f = File(ctx.filesDir, "staging/${p.id}.bin")
        if (!f.exists()) return err(Response.Status.GONE, "staged_copy_missing")
        val r = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/octet-stream", FileInputStream(f), f.length())
        r.addHeader("X-Sha256", p.sha256 ?: "")
        return r
    }

    private fun ack(id: String, b: JSONObject): Response {
        val p = db.photoById(id) ?: return err(Response.Status.NOT_FOUND, "no_such_photo")
        if (p.state == "ACKNOWLEDGED") return json(Response.Status.OK, JSONObject().put("ok", true).put("duplicate", true))
        if (!b.optString("sha256").equals(p.sha256, ignoreCase = true) || b.optLong("size", -1) != p.size)
            return err(Response.Status.CONFLICT, "checksum_mismatch")
        db.ack(id) // durable first, then delete the staged copy
        File(ctx.filesDir, "staging/${p.id}.bin").delete()
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun nack(id: String, b: JSONObject): Response {
        if (db.photoById(id) == null) return err(Response.Status.NOT_FOUND, "no_such_photo")
        db.nack(id, b.optBoolean("retry", true), b.optString("reason", "receiver rejected"), maxAttempts)
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }
}
