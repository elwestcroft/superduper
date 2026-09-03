package com.superduper.notes.engine

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View

/**
 * Startup capability gate for the firmware pen-write engine (SPEC.md §0.2).
 *
 * Every capability this app depends on is undocumented reflection into one vendor
 * firmware build. A Chauvet update can remove `getPWInterFace()` or change a signature,
 * and the failure mode is silent — we lost hours to `unDo()` doing nothing because it was
 * called with the wrong arity and the exception was swallowed.
 *
 * So: probe once at startup, resolve every method we intend to call, and record the
 * firmware build alongside the result. If something is missing we want a loud, specific
 * log line naming it — not mysterious dead buttons.
 */
object EngineCapabilities {

    private const val TAG = "SuperDuper"

    /** Methods we rely on, with their exact parameter types. Order is documentation. */
    private val REQUIRED: List<Pair<String, Array<Class<*>>>> = listOf(
        "setPWEnabled" to arrayOf(Boolean::class.javaPrimitiveType!!),
        "setDrawObjectType" to arrayOf(Int::class.javaPrimitiveType!!),
        "setPenType" to arrayOf(Int::class.javaPrimitiveType!!),
        "setPenStdWidth" to arrayOf(Float::class.javaPrimitiveType!!),
        "setPenColor" to arrayOf(Int::class.javaPrimitiveType!!),
        "setFingerWritable" to arrayOf(Boolean::class.javaPrimitiveType!!),
        // unDo takes a boolean; the no-arg form does not exist and fails silently.
        "unDo" to arrayOf(Boolean::class.javaPrimitiveType!!),
        "reDo" to emptyArray(),
        "getAvailableUndo" to emptyArray(),
        "getAvailableRedo" to emptyArray(),
        "getStepPointArray" to arrayOf(Int::class.javaPrimitiveType!!),
        "setLoadFilePath" to arrayOf(String::class.java),
        "saveBitmapAndWaitDone" to arrayOf(Long::class.javaPrimitiveType!!),
        "addUnWriteRect" to arrayOf(android.graphics.Rect::class.java),
        "clearUnWriteRectList" to emptyArray(),
        "clearContentX" to arrayOf(Boolean::class.javaPrimitiveType!!),
    )

    data class Report(
        val engineAvailable: Boolean,
        val missing: List<String>,
        val firmware: String,
    ) {
        /** Usable at all — ink and paging work even if a nicety is missing. */
        val usable: Boolean get() = engineAvailable && missing.none { it in CRITICAL }
    }

    private val CRITICAL = setOf("setPWEnabled", "setDrawObjectType", "setLoadFilePath")

    /** The firmware build this app's reflection was verified against. */
    const val VERIFIED_FIRMWARE = "Chauvet.E103.2606141001.2389_release"

    fun probe(context: Context, host: View): Report {
        val firmware = runCatching {
            @Suppress("PrivateApi")
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, "ro.product.internal.version") as? String
        }.getOrNull().orEmpty().ifBlank { Build.DISPLAY }

        if (firmware != VERIFIED_FIRMWARE) {
            Log.w(TAG, "CAP: firmware is '$firmware' but reflection was verified against " +
                "'$VERIFIED_FIRMWARE' — re-verify engine behaviour before trusting it")
        }

        val engine = runCatching { View::class.java.getMethod("getPWInterFace").invoke(host) }
            .getOrNull()
        if (engine == null) {
            Log.e(TAG, "CAP: getPWInterFace() unavailable on '$firmware' — no firmware ink")
            return Report(false, REQUIRED.map { it.first }, firmware)
        }

        val missing = REQUIRED.filter { (name, params) ->
            runCatching { engine.javaClass.getMethod(name, *params) }.isFailure
        }.map { "${it.first}(${it.second.joinToString(",") { c -> c.simpleName }})" }

        missing.forEach { Log.e(TAG, "CAP: MISSING $it") }
        Log.i(TAG, "CAP: engine=${engine.javaClass.name} firmware='$firmware' " +
            "missing=${missing.size}/${REQUIRED.size}")
        return Report(true, missing, firmware)
    }
}
