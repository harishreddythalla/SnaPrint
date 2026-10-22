package com.snaptoprint

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class MediaRowData(val id: Long, val gen: Long?, val taken: Long?, val name: String?, val mime: String?, val size: Long)

object MediaQuery {
    const val DEFAULT_PATH = "DCIM/Camera/"
    private val uri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val proj = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.GENERATION_ADDED,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE
    )

    fun version(ctx: Context): String = MediaStore.getVersion(ctx, MediaStore.VOLUME_EXTERNAL_PRIMARY)
    fun generation(ctx: Context): Long = MediaStore.getGeneration(ctx, MediaStore.VOLUME_EXTERNAL_PRIMARY)
    fun contentUri(id: Long): Uri = ContentUris.withAppendedId(uri, id)

    private fun q(ctx: Context, sel: String, args: Array<String>): List<MediaRowData> {
        val out = ArrayList<MediaRowData>()
        ctx.contentResolver.query(uri, proj, sel, args, "${MediaStore.MediaColumns._ID} ASC")?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    MediaRowData(
                        c.getLong(0),
                        if (c.isNull(1)) null else c.getLong(1),
                        if (c.isNull(2)) null else c.getLong(2),
                        c.getString(3), c.getString(4), c.getLong(5)
                    )
                )
            }
        }
        return out
    }

    private val base = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING}=0"

    /** Ids already in the library at or before [gen]; this is the event-start baseline. */
    fun idsUpToGeneration(ctx: Context, path: String, gen: Long): List<Long> =
        q(ctx, "$base AND ${MediaStore.MediaColumns.GENERATION_ADDED} <= ?", arrayOf("$path%", gen.toString())).map { it.id }

    /** Camera rows; if [minGenExclusive] is given, only rows added after it. */
    fun rows(ctx: Context, path: String, minGenExclusive: Long?): List<MediaRowData> =
        if (minGenExclusive != null)
            q(ctx, "$base AND ${MediaStore.MediaColumns.GENERATION_ADDED} > ?", arrayOf("$path%", minGenExclusive.toString()))
        else q(ctx, base, arrayOf("$path%"))

    fun byId(ctx: Context, id: Long): MediaRowData? =
        q(ctx, "${MediaStore.MediaColumns._ID}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0", arrayOf(id.toString())).firstOrNull()
}
