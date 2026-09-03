package com.superduper.notes.doc

import android.util.Log
import java.io.File

/**
 * Reader/writer for the firmware's `.tch` stroke format.
 *
 * Being able to *author* this format is what makes a continuous canvas possible. The
 * engine's writing surface is one screen (M-A), so a document taller than the screen has
 * to be windowed — and because `setLoadFilePath` restores a full, editable vector model,
 * a window we generate is indistinguishable to the engine from one it saved itself.
 * Verified on-device: a `.tch` with every Y shifted by -400 loaded in 34 ms and rendered
 * exactly 400 px higher, with erasers and undo intact.
 *
 * Format (big-endian, decompiled and confirmed byte-for-byte against a real save):
 *
 *   header, 8 bytes:  int16 magic = 0xEAAE
 *                     int16 version = 0x0102
 *                     int16 headerSize = 8
 *                     int16 pointSize = 18
 *
 *   record, 18 bytes: int16 action     — 0x0000 down, 0x0002 move; 0x7Cxx = config
 *                     int16 x
 *                     int16 y
 *                     int16 radius
 *                     int16 erasing
 *                     int32 repeat
 *                     float pressure
 *
 * A stroke begins with a **config record** (`action & 0xFF00 == 0x7C00`) whose x/y/radius
 * /pressure fields are not coordinates at all — they carry the draw-object type and the
 * canvas dimensions. Translating those corrupts the file, so they are copied verbatim.
 */
object TchFile {

    private const val TAG = "SuperDuper"
    const val MAGIC = 0xEAAE.toShort()
    const val VERSION = 0x0102.toShort()
    const val HEADER_SIZE = 8
    const val POINT_SIZE = 18

    private const val CONFIG_MASK = 0xFF00
    private const val CONFIG_TAG = 0x7C00

    /** One 18-byte record, kept raw so unknown fields survive a round trip untouched. */
    class Record(val bytes: ByteArray) {
        init { require(bytes.size == POINT_SIZE) }

        val action: Int get() = be16(bytes, 0)
        val isConfig: Boolean get() = (action and CONFIG_MASK) == CONFIG_TAG
        // Read-only on purpose. `translated()` shares the point list between strokes, so
        // a setter here would corrupt the original and silently invalidate its cached
        // bounds. Points are built once in buildStroke and never mutated after.
        val x: Int get() = be16signed(bytes, 2)
        val y: Int get() = be16signed(bytes, 4)
        val pressure: Float
            get() = Float.fromBits(
                (bytes[14].toInt() and 0xFF shl 24) or (bytes[15].toInt() and 0xFF shl 16) or
                    (bytes[16].toInt() and 0xFF shl 8) or (bytes[17].toInt() and 0xFF)
            )

        fun copy() = Record(bytes.copyOf())
    }

    /** A stroke: its leading config record plus its points. */
    /**
     * A stroke, positioned by an **int32 origin** with its points held relative to it.
     *
     * Records pack y as int16 because that is the engine's format. Storing absolute
     * document coordinates in them capped the canvas at y=32767 — roughly 19 screens —
     * after which strokes wrapped negative, vanished from every y-range filter and
     * persisted as corrupt geometry. A single stroke never spans more than a screen, so
     * relative values stay far inside int16 while [originY] carries the document position.
     *
     * It also makes translation O(1): scrolling adjusts the origin instead of rewriting
     * every point.
     */
    class Stroke(val config: Record?, val points: MutableList<Record>, val originY: Int = 0) {

        // Bounds are cached, not computed per access. They were properties that scanned
        // every point, and the eraser reads both for every stroke on every sample — a
        // two-second drag over a few hundred strokes was hundreds of millions of ops on
        // the UI thread.
        val minY: Int
        val maxY: Int
        val minX: Int
        val maxX: Int

        init {
            var nY = Int.MAX_VALUE; var xY = Int.MIN_VALUE
            var nX = Int.MAX_VALUE; var xX = Int.MIN_VALUE
            points.forEach { p ->
                if (p.y < nY) nY = p.y
                if (p.y > xY) xY = p.y
                if (p.x < nX) nX = p.x
                if (p.x > xX) xX = p.x
            }
            if (points.isEmpty()) { nY = 0; xY = 0; nX = 0; xX = 0 }
            minY = originY + nY; maxY = originY + xY; minX = nX; maxX = xX
        }

        /**
         * The nib this stroke was drawn with, from its config record.
         *
         * Defaults to [PenStyle.PEN] when there is no config record or the value is
         * unrecognised, which is what every stroke saved before styles existed reads as.
         */
        val style: PenStyle get() = PenStyle.fromObjType(config?.x ?: PenStyle.PEN.objType)

        /**
         * The std width this stroke was drawn at, or null if the document predates the
         * field. Callers substitute the current pen weight when it is null.
         */
        val stdWidth: Float? get() = (config?.y ?: 0).takeIf { it > 0 }?.let { it / 10f }

        /** Round-trip form of [stdWidth] for rebuilding this stroke. */
        val widthTenths: Int get() = config?.y ?: 0

        /** Absolute y of a point, resolving the origin. */
        fun yOf(p: Record): Int = originY + p.y

        /** O(1): shares the point list and only moves the origin. */
        fun translated(dy: Int): Stroke = Stroke(config, points, originY + dy)
    }

    /**
     * Build a stroke from captured points, in the format the engine writes.
     *
     * Points are (x, y, pressure) in whatever coordinate space the caller is working in —
     * the document keeps them in world space, the renderer translates on the way out.
     * The leading config record carries the draw-object type and canvas dimensions, so it
     * is synthesised here rather than copied.
     */
    fun buildStroke(
        points: List<FloatArray>,
        canvasW: Int,
        canvasH: Int,
        objType: Int = PenStyle.PEN.objType,
        widthTenths: Int = 0,
    ): Stroke? {
        if (points.isEmpty()) return null
        val cfg = Record(ByteArray(POINT_SIZE)).apply {
            putBe16(bytes, 0, CONFIG_TAG)   // config marker
            // The nib this stroke was drawn with, in the slot the format already reserves
            // for it. Strokes written before styles existed have 0 here, which is
            // PenStyle.PEN — so old documents read back correctly with no migration.
            putBe16(bytes, 2, objType)
            // And the std width it was drawn at, in tenths of a pixel.
            //
            // Per stroke, not per document: the pen weight is a live setting, and without
            // this every stroke already on the page silently re-rendered at whatever weight
            // was selected now — so switching pens changed the thickness of old ink.
            // Zero means "not recorded" (strokes saved before this existed), and the
            // renderer falls back to the current setting for those.
            putBe16(bytes, 4, widthTenths)
            putBe16(bytes, 6, canvasH)
            putFloat(bytes, 14, canvasW.toFloat())
        }
        // Anchor the stroke at its own top edge and store points relative, so document
        // depth is carried by the int32 origin rather than the int16 record field.
        val origin = points.minOf { it[1] }.toInt()
        val recs = points.mapIndexed { i, p ->
            Record(ByteArray(POINT_SIZE)).apply {
                putBe16(bytes, 0, if (i == 0) ACTION_DOWN else ACTION_MOVE)
                putBe16(bytes, 2, p[0].toInt())
                putBe16(bytes, 4, p[1].toInt() - origin)
                putBe16(bytes, 6, 1)
                putFloat(bytes, 14, p[2])
            }
        }.toMutableList()
        return Stroke(cfg, recs, origin)
    }

    fun read(file: File): MutableList<Stroke> {
        if (!file.exists() || file.length() < HEADER_SIZE) return mutableListOf()
        val b = file.readBytes()
        val magic = be16(b, 0).toShort()
        val headerSize = be16(b, 4)
        val pointSize = be16(b, 6)
        if (magic != MAGIC || headerSize != HEADER_SIZE || pointSize != POINT_SIZE) {
            Log.w(TAG, "TCH: unexpected header in ${file.name} " +
                "magic=0x${Integer.toHexString(magic.toInt() and 0xFFFF)} " +
                "hdr=$headerSize pt=$pointSize — refusing to parse")
            return mutableListOf()
        }

        val strokes = mutableListOf<Stroke>()
        var current: Stroke? = null
        var off = headerSize
        while (off + POINT_SIZE <= b.size) {
            val rec = Record(b.copyOfRange(off, off + POINT_SIZE))
            if (rec.isConfig) {
                current?.let { if (it.points.isNotEmpty()) strokes.add(it) }
                current = Stroke(rec, mutableListOf())
            } else {
                if (current == null) current = Stroke(null, mutableListOf())
                current.points.add(rec)
            }
            off += POINT_SIZE
        }
        current?.let { if (it.points.isNotEmpty()) strokes.add(it) }
        return strokes
    }

    private fun be16(b: ByteArray, i: Int) =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun be16signed(b: ByteArray, i: Int) = be16(b, i).toShort().toInt()

    const val ACTION_DOWN = 0x0000
    const val ACTION_MOVE = 0x0002

    private fun putFloat(b: ByteArray, i: Int, v: Float) {
        val bits = v.toRawBits()
        b[i] = (bits ushr 24 and 0xFF).toByte()
        b[i + 1] = (bits ushr 16 and 0xFF).toByte()
        b[i + 2] = (bits ushr 8 and 0xFF).toByte()
        b[i + 3] = (bits and 0xFF).toByte()
    }

    private fun putBe16(b: ByteArray, i: Int, v: Int) {
        b[i] = ((v shr 8) and 0xFF).toByte()
        b[i + 1] = (v and 0xFF).toByte()
    }
}
