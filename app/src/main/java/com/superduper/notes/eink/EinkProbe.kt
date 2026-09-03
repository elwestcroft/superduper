package com.superduper.notes.eink

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import java.io.File
import java.lang.reflect.Method

/**
 * Capability probe for the undocumented Chauvet OS e-ink framework APIs (SPEC.md §2.2,
 * risk #1). Read-only apart from one deliberately benign write (a screen refresh).
 *
 * Background established by static analysis of this device's firmware
 * (Chauvet.E103.2606141001.2389_release):
 *
 *  - /system/framework/{htfypw,htfyOpt,htfyview,libeinkpwcoreapi}.jar are all on the
 *    BOOTCLASSPATH, so their classes are loaded in every app process.
 *  - android.os.EinkManager exposes setMode(String)/getMode() over the waveform vocabulary
 *    in android.os.EinkManager$EinkMode (EPD_A2, EPD_DU, EPD_FULL_GC16, EPD_PART_GL16, ...),
 *    plus screenRefresh(boolean,int), sendHwcCmd(int,int[]), setScreenMode(int,boolean).
 *  - android.view.OwnerSurfaceView extends View implements android.view.EinkPWInterface and
 *    is the native pen-write engine's host view (~170 methods: pen types, pressure curves,
 *    eraser, lasso via addSelectObj, undo/redo, scroll rects, save/load).
 *  - Crucially, hiddenapi flags are asymmetric: virtually every METHOD is flagged "sdk"
 *    (unrestricted) while every CONSTRUCTOR and static factory is flagged BLOCKED. So the
 *    problem is obtaining instances, not calling them.
 *
 * The probe therefore tries, in increasing order of intrusiveness, to obtain instances, and
 * reports exactly what the platform allows. Everything is logged under tag "SuperDuper".
 */
object EinkProbe {

    private const val TAG = "SuperDuper"

    private val report = StringBuilder()

    private fun ok(step: String, detail: String) {
        Log.i(TAG, "PROBE  OK   $step :: $detail")
        report.append("OK   $step :: $detail\n")
    }

    private fun no(step: String, t: Throwable?) {
        val d = t?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "unavailable"
        Log.w(TAG, "PROBE  FAIL $step :: $d")
        report.append("FAIL $step :: $d\n")
    }

    private fun info(step: String, detail: String) {
        Log.i(TAG, "PROBE  --   $step :: $detail")
        report.append("--   $step :: $detail\n")
    }

    /** Runs the whole probe. Returns a human-readable report. */
    fun run(context: Context): String {
        report.setLength(0)
        Log.i(TAG, "PROBE  ===== e-ink capability probe start =====")

        systemFeatures(context)
        val einkManager = einkManagerAccess(context)
        einkModeVocabulary()
        if (einkManager != null) benignRefresh(einkManager)
        pwClassPresence()
        val inflated = inflateOwnerSurfaceView(context)
        if (inflated == null) {
            directConstructor(context)
            exemptThenConstruct(context)
        }
        ebcNode()

        viewWaveformApi()
        viewWaveformConstants()
        Log.i(TAG, "PROBE  ===== e-ink capability probe end =====")
        return report.toString()
    }

    // 1. Is this even an e-ink platform, per the device's own declaration?
    /**
     * S3 — does this firmware expose PER-RECT waveform control on android.view.View?
     *
     * SPEC recorded these as a verified capability. They are not: they appear in one
     * suggestive sentence with a cross-reference to a section that does not discuss them,
     * and nowhere in our code. Whether an app can choose its own waveform per dirty rect
     * decides whether an app-owned renderer can compete with the firmware engine, so it
     * needs to be established rather than assumed.
     */
    private fun viewWaveformApi() {
        val v = android.view.View::class.java
        val candidates = listOf<Triple<String, Array<Class<*>>, String>>(
            Triple("setEinkUpdateMode", arrayOf(Int::class.javaPrimitiveType!!), "dispMode"),
            Triple("setEinkUpdateMode", arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!), "dataMode,dispMode"),
            Triple("resetEinkUpdateMode", emptyArray(), ""),
            Triple("setEinkA2Gate", arrayOf(Int::class.javaPrimitiveType!!), "gate"),
            Triple("getEinkA2Gate", emptyArray(), ""),
            Triple("getEinkDataMode", emptyArray(), ""),
            Triple("invalidate", arrayOf(android.graphics.Rect::class.java, Int::class.javaPrimitiveType!!), "rect,einkMode"),
            Triple("invalidateOnDraw", arrayOf(Int::class.javaPrimitiveType!!), "einkMode"),
            Triple("forceEinkFullUpdate", arrayOf(Boolean::class.javaPrimitiveType!!), "afterHide"),
            Triple("setBoldTextMode", emptyArray(), ""),
            Triple("freezeInvalidate", arrayOf(Boolean::class.javaPrimitiveType!!), "freeze"),
        )
        var found = 0
        candidates.forEach { (name, params, argNames) ->
            val sig = "$name(${params.joinToString(",") { it.simpleName }})"
            try {
                v.getMethod(name, *params)
                found++
                ok("View.$sig", if (argNames.isEmpty()) "present" else "present [$argNames]")
            } catch (t: Throwable) {
                no("View.$sig", null)
            }
        }
        info("per-rect waveform API", "$found/${candidates.size} present — " +
            if (found >= 3) "app-owned renderer CAN choose waveforms"
            else "app-owned renderer is stuck with the display service's choice")
    }

    /** Read the real EINK_* constant values off android.view.View rather than guessing. */
    private fun viewWaveformConstants() {
        val v = android.view.View::class.java
        val fields = v.fields
            .filter { it.name.startsWith("EINK_") }
            .sortedBy { it.name }
        if (fields.isEmpty()) { no("View EINK_* constants", null); return }
        fields.forEach { f ->
            runCatching { ok("const ${f.name}", "${f.get(null)}") }
        }
        info("View EINK_* constants", "${fields.size} found")
    }

    private fun systemFeatures(context: Context) {
        val pm = context.packageManager
        for (f in arrayOf("android.software.eink", "android.software.hteink")) {
            if (pm.hasSystemFeature(f)) ok("feature($f)", "declared") else no("feature($f)", null)
        }
    }

    // 2. getSystemService("eink") is a public SDK call; the returned type is not public SDK.
    private fun einkManagerAccess(context: Context): Any? {
        val mgr = try {
            context.getSystemService("eink")
        } catch (t: Throwable) {
            no("getSystemService(\"eink\")", t); return null
        }
        if (mgr == null) {
            no("getSystemService(\"eink\")", null); return null
        }
        ok("getSystemService(\"eink\")", mgr.javaClass.name)

        // Read-only calls first: these prove whether "sdk"-flagged members really are
        // reachable by reflection from an untrusted app on API 30.
        for (getter in arrayOf("getMode", "getEinkEnabled", "isValid")) {
            try {
                val m: Method = mgr.javaClass.getMethod(getter)
                ok("EinkManager.$getter()", "${m.invoke(mgr)}")
            } catch (t: Throwable) {
                no("EinkManager.$getter()", t)
            }
        }
        // Confirm the interesting mutators resolve, without calling them.
        for ((name, params) in listOf(
            "setMode" to arrayOf<Class<*>>(String::class.java),
            "screenRefresh" to arrayOf<Class<*>>(Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            "sendHwcCmd" to arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, IntArray::class.java),
            "setScreenMode" to arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            "setDitherType" to arrayOf<Class<*>>(Int::class.javaPrimitiveType!!),
            "sendOneFullFrame" to arrayOf<Class<*>>(),
        )) {
            try {
                mgr.javaClass.getMethod(name, *params)
                ok("resolve EinkManager.$name", "reachable")
            } catch (t: Throwable) {
                no("resolve EinkManager.$name", t)
            }
        }
        return mgr
    }

    // 3. The waveform constants — these tell us the exact mode strings setMode() accepts.
    private fun einkModeVocabulary() {
        try {
            val cls = Class.forName("android.os.EinkManager\$EinkMode")
            val modes = cls.declaredFields
                .filter { it.name.startsWith("EPD_") }
                .mapNotNull { f ->
                    try {
                        f.isAccessible = true
                        "${f.name}=${f.get(null)}"
                    } catch (t: Throwable) {
                        "${f.name}=<${t.javaClass.simpleName}>"
                    }
                }
            ok("EinkMode constants (${modes.size})", modes.joinToString(", "))
        } catch (t: Throwable) {
            no("EinkMode constants", t)
        }
    }

    /**
     * One deliberate write: force a screen refresh. Chosen because it is the single safest
     * mutator — it has an immediately visible effect, leaves no persistent state, and is
     * exactly the ghosting lever SPEC.md §4.5 wants. Waveform/mode changes are global and
     * are NOT exercised here.
     */
    private fun benignRefresh(mgr: Any) {
        try {
            val m = mgr.javaClass.getMethod(
                "screenRefresh",
                Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!
            )
            m.invoke(mgr, false, 0)
            ok("CALL EinkManager.screenRefresh(false,0)", "invoked — watch for a full flash")
        } catch (t: Throwable) {
            no("CALL EinkManager.screenRefresh", t)
        }
    }

    // 4. Class loading is never hidden-api restricted; only member access is.
    private fun pwClassPresence() {
        for (n in arrayOf(
            "android.view.OwnerSurfaceView",
            "android.view.EinkPWInterface",
            "android.view.PWInputPoint",
            "android.view.GVWindow",
            "android.view.SFCommandX",
        )) {
            try {
                Class.forName(n); ok("class present", n)
            } catch (t: Throwable) {
                no("class present $n", t)
            }
        }
    }

    /**
     * The interesting one: let the framework's LayoutInflater construct OwnerSurfaceView.
     * The blocked constructor is then invoked by bootclasspath code, not by ours.
     */
    private fun inflateOwnerSurfaceView(context: Context): View? {
        return try {
            val id = context.resources.getIdentifier("probe_pw", "layout", context.packageName)
            val root = LayoutInflater.from(context).inflate(id, null, false)
            val pw = root.findViewById<View>(
                context.resources.getIdentifier("pw_view", "id", context.packageName)
            )
            if (pw == null) {
                no("inflate OwnerSurfaceView", null); null
            } else {
                ok("inflate OwnerSurfaceView", "instance = ${pw.javaClass.name}")
                probePwMethods(pw)
                pw
            }
        } catch (t: Throwable) {
            no("inflate OwnerSurfaceView", t); null
        }
    }

    /** With an instance in hand, verify the "sdk"-flagged engine methods actually invoke. */
    private fun probePwMethods(pw: View) {
        val c = pw.javaClass
        for ((name, params, args) in listOf(
            Triple("getAvailableUndo", arrayOf<Class<*>>(), arrayOf<Any?>()),
            Triple("getPenType", arrayOf<Class<*>>(), arrayOf<Any?>()),
            Triple("getPenStdWidth", arrayOf<Class<*>>(), arrayOf<Any?>()),
            Triple("isCurrentWriting", arrayOf<Class<*>>(), arrayOf<Any?>()),
            Triple("getPWBitmapFilePath", arrayOf<Class<*>>(), arrayOf<Any?>()),
        )) {
            try {
                ok("PW.$name()", "${c.getMethod(name, *params).invoke(pw, *args)}")
            } catch (t: Throwable) {
                no("PW.$name()", t)
            }
        }
        // Resolve-only: the calls that would actually enable native ink.
        for ((name, params) in listOf(
            "setPWEnabled" to arrayOf<Class<*>>(Boolean::class.javaPrimitiveType!!),
            "setHostView" to arrayOf<Class<*>>(View::class.java),
            "setPenType" to arrayOf<Class<*>>(Int::class.javaPrimitiveType!!),
            "setPenStdWidth" to arrayOf<Class<*>>(Float::class.javaPrimitiveType!!),
            "unDo" to arrayOf<Class<*>>(),
            "getPureWriteBitmap" to arrayOf<Class<*>>(),
        )) {
            try {
                c.getMethod(name, *params); ok("resolve PW.$name", "reachable")
            } catch (t: Throwable) {
                no("resolve PW.$name", t)
            }
        }
    }

    // 5. Baseline: prove the direct path really is blocked, and capture the exact error.
    private fun directConstructor(context: Context) {
        try {
            val cls = Class.forName("android.view.OwnerSurfaceView")
            val ctor = cls.getConstructor(Context::class.java)
            ok("direct constructor", "obtained ${ctor}")
        } catch (t: Throwable) {
            no("direct constructor (expected: blocked)", t)
        }
    }

    /**
     * Last resort: ask ART to exempt hidden APIs for this process, via double reflection so
     * the immediate caller of getDeclaredMethod is java.lang.reflect.Method (bootclasspath)
     * rather than our own dex. Documented to work on API 28-30.
     */
    private fun exemptThenConstruct(context: Context) {
        try {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java
            )
            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethod.invoke(
                vmRuntimeClass, "getRuntime", arrayOfNulls<Class<*>>(0)
            ) as Method
            val setExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass, "setHiddenApiExemptions", arrayOf<Class<*>>(Array<String>::class.java)
            ) as Method
            val runtime = getRuntime.invoke(null)
            setExemptions.invoke(runtime, arrayOf(arrayOf("L")))
            ok("setHiddenApiExemptions", "applied")

            val cls = Class.forName("android.view.OwnerSurfaceView")
            val v = cls.getConstructor(Context::class.java).newInstance(context) as View
            ok("constructor after exemption", v.javaClass.name)
            probePwMethods(v)
        } catch (t: Throwable) {
            no("setHiddenApiExemptions path", t)
        }
    }

    // 6. The raw EBC device node — POSIX mode allows it; SELinux is the real gate.
    private fun ebcNode() {
        val f = File("/dev/ebc")
        info("/dev/ebc", "exists=${f.exists()} canRead=${f.canRead()} canWrite=${f.canWrite()}")
        try {
            f.inputStream().use { it.read() }
            ok("open /dev/ebc", "readable")
        } catch (t: Throwable) {
            no("open /dev/ebc (SELinux expected to deny)", t)
        }
    }
}
