package com.snaptoprint

object Status {
    fun summary(db: Db): String {
        val err = db.getKv("error")
        if (err != null) return "TRANSFER PAUSED · $err"
        val s = db.currentSession()
        val pending = db.count("SELECT COUNT(*) FROM photo WHERE state IN $UNDELIVERED")
        val conn = Link.connected()
        return when (s?.state) {
            "STARTING" -> "WAITING FOR LAPTOP · event not started yet"
            "ACTIVE" -> if (conn) "EVENT ACTIVE · CONNECTED · $pending PENDING"
                        else if (pending > 0) "EVENT ACTIVE · DISCONNECTED · $pending PHOTOS QUEUED"
                        else "EVENT ACTIVE · DISCONNECTED · 0 PENDING"
            "FINISHING" -> "FINISHING EVENT…"
            else -> if (pending > 0) "EVENT ENDED · $pending PHOTOS STILL TO SEND"
                    else if (conn) "READY TO START · LAPTOP CONNECTED"
                    else "NOT READY · LAPTOP NOT CONNECTED"
        }
    }
}
