package com.superduper.notes.doc

import android.util.Log
import java.io.File

/**
 * On-disk container for the document.
 *
 * The engine's `.tch` format stores point coordinates as **int16**, which caps a document
 * at y = 32767 — about 19 screens. That is fine for the one-page notes it was designed for
 * and fatal for an infinite canvas: the first stroke drawn below the cap wraps negative,
 * disappears from every y-range filter, and persists as corrupt geometry.
 *
 * So each stroke is stored with an **int32 origin** and its points held relative to that
 * origin. A single stroke never spans more than a screen, so the relative values stay well
 * inside int16 and the record layout stays byte-identical to `.tch` — which keeps
 * [TchFile] usable for exporting a window in the engine's own format.
 *
 * Writes are atomic: a temp file, flushed to disk, then renamed. `File.writeBytes`
 * truncates before writing, so an interrupted save previously left a truncated file that
 * the reader rejected as unparseable — and the next save then wrote an empty document over
 * it, making the loss permanent and silent.
 */
object DocFile {

    private const val TAG = "SuperDuper"
    private const val MAGIC = 0x53444F43 // "SDOC"
    private const val VERSION = 1

    /** Returns true only if the document is safely on disk. */
    fun write(file: File, strokes: List<TchFile.Stroke>): Boolean {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            java.io.FileOutputStream(tmp).use { fos ->
                val out = java.io.BufferedOutputStream(fos)
                val d = java.io.DataOutputStream(out)
                d.writeInt(MAGIC)
                d.writeInt(VERSION)
                d.writeInt(strokes.size)
                strokes.forEach { s ->
                    // Points are already relative to the stroke's origin in memory.
                    d.writeInt(s.originY)
                    d.writeInt(s.points.size)
                    s.config?.let { d.writeByte(1); d.write(it.bytes) } ?: d.writeByte(0)
                    s.points.forEach { p -> d.write(p.bytes) }
                }
                d.flush()
                out.flush()
                // Reach the platter before the rename, or a crash can leave the rename
                // visible with the contents still buffered.
                fos.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                Log.e(TAG, "DOC: rename failed, document left at ${tmp.name}")
                return false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "DOC: save failed: $t")
            tmp.delete()
            return false
        }
        return true
    }

    /** Outcome of a read, so the caller can tell "empty document" from "could not read". */
    class Result(val strokes: MutableList<TchFile.Stroke>, val ok: Boolean)

    fun read(file: File): Result {
        val out = mutableListOf<TchFile.Stroke>()
        if (!file.exists()) return Result(out, true)
        if (file.length() < 12) return Result(out, false)
        try {
            java.io.DataInputStream(java.io.BufferedInputStream(file.inputStream())).use { d ->
                if (d.readInt() != MAGIC) {
                    preserve(file, "magic")
                    return Result(out, false)
                }
                val version = d.readInt()
                if (version != VERSION) {
                    // Also a preserve-and-refuse case. Returning empty without preserving
                    // meant the next autosave overwrote a document we simply could not
                    // read — the same loss the atomic write was meant to prevent.
                    Log.w(TAG, "DOC: version $version (expected $VERSION)")
                    preserve(file, "version")
                    return Result(out, false)
                }
                // A stroke count or point count is never more than the file itself could
                // possibly hold at one point per TchFile.POINT_SIZE bytes — a cheap, exact
                // ceiling that catches a corrupted length field (a bad SD-card write, an
                // interrupted save) before it drives an allocation, rather than after.
                // Security audit, 2026-09-03: previously `n`/`count` sized an ArrayList
                // straight from the file with no such check, so a single flipped bit could
                // demand a huge allocation before the (much smaller) real data — caught by
                // the catch block below either way, but only after the attempt.
                val maxPossiblePoints = (file.length() / TchFile.POINT_SIZE).toInt().coerceAtLeast(0)
                val count = d.readInt()
                if (count < 0 || count > maxPossiblePoints) {
                    preserve(file, "stroke count")
                    return Result(out, false)
                }
                repeat(count) {
                    val origin = d.readInt()
                    val n = d.readInt()
                    if (n < 0 || n > maxPossiblePoints) {
                        preserve(file, "point count")
                        return Result(out, false)
                    }
                    val cfg = if (d.readByte().toInt() == 1) {
                        TchFile.Record(ByteArray(TchFile.POINT_SIZE).also { d.readFully(it) })
                    } else null
                    val pts = ArrayList<TchFile.Record>(n)
                    repeat(n) {
                        pts.add(TchFile.Record(ByteArray(TchFile.POINT_SIZE).also { d.readFully(it) }))
                    }
                    out.add(TchFile.Stroke(cfg, pts, origin))
                }
            }
        } catch (t: Throwable) {
            // A partial read is NOT a valid document: saving it back would permanently
            // discard the tail we failed to parse.
            Log.e(TAG, "DOC: read failed after ${out.size} strokes: $t")
            preserve(file, "truncated")
            return Result(out, false)
        }
        return Result(out, true)
    }

    /** Keep a copy of an unreadable document, without clobbering an earlier one. */
    private fun preserve(file: File, why: String) {
        val dir = file.parentFile ?: return
        var n = 0
        var dest = File(dir, file.name + ".bad")
        while (dest.exists() && n < 20) { n++; dest = File(dir, file.name + ".bad$n") }
        runCatching { file.copyTo(dest, overwrite = false) }
            .onSuccess { Log.w(TAG, "DOC: unreadable ($why); preserved as ${dest.name}") }
            .onFailure { Log.e(TAG, "DOC: could not preserve $why copy: $it") }
    }
}
