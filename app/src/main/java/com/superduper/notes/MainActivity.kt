package com.superduper.notes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.superduper.notes.eink.EinkRefresh
import com.superduper.notes.eink.NativeInkView
import com.superduper.notes.engine.EngineCapabilities
import com.superduper.notes.doc.ScrollingDocument
import com.superduper.notes.ui.InkToolbar
import java.io.File

/**
 * Milestone B (SPEC.md §0.6): a single screen-sized page with the full engine tool set —
 * the first build that is a usable notepad rather than a test harness.
 *
 * Layout is a vertical stack: ink host on top, toolbar strip beneath. The toolbar must be
 * a SIBLING of the ink host (the engine inks over its entire host view) and is
 * additionally fenced with `addUnWriteRect`, so a stray stroke cannot draw on the buttons.
 *
 * M-A settled the page geometry: the host view is exactly one screen. A taller view makes
 * the engine's native path render garbage — jagged strokes, latency, duplication
 * (SPEC.md §0.7).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ink: NativeInkView
    private lateinit var toolbar: InkToolbar
    private var caps: EngineCapabilities.Report? = null
    private var doc: ScrollingDocument? = null
    private val settingsPopup by lazy {
        com.superduper.notes.ui.SettingsPopup(this) { style, spacing -> doc?.setBackground(style, spacing) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "M-B: starting")

        ink = NativeInkView(this)
        toolbar = InkToolbar(this)
        val toolbarPx = (InkToolbar.HEIGHT_DP * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            // Toolbar above the canvas. At the bottom it sat under the writing hand and
            // was the first thing a resting palm found; at the top it is out of the way of
            // a right-hander's arc and matches where the native app puts its docked bar.
            addView(toolbar, LinearLayout.LayoutParams(MATCH, toolbarPx))
            addView(ink, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        setContentView(root)
        root.keepScreenOn = true
        showSplash()

        toolbar.onTool = { tool ->
            Log.i(TAG, "DIAG toolbar tapped: $tool")
            // Tell the engine too, so it stops laying down ink while erasing; the erase
            // that counts happens against our own model.
            ink.setTool(tool.drawObjType)
            // One assignment drives engine state, gesture feedback and erase behaviour.
            ink.penMode = when (tool) {
                InkToolbar.Tool.PEN, InkToolbar.Tool.PEN2 -> NativeInkView.PenMode.PEN
                InkToolbar.Tool.ERASE_SPOT -> NativeInkView.PenMode.ERASE_SPOT
                InkToolbar.Tool.ERASE_AREA -> NativeInkView.PenMode.ERASE_AREA
                InkToolbar.Tool.LASSO -> NativeInkView.PenMode.LASSO
            }
            doc?.lassoMode = tool == InkToolbar.Tool.LASSO
            // Selecting a pen slot applies that slot's nib and weight, so the two slots
            // genuinely hold different pens rather than sharing one global setting.
            toolbar.slots[tool]?.let { slot ->
                ink.penStyle = slot.style
                ink.penWidth = slot.width
            }
            doc?.eraseMode = if (tool == InkToolbar.Tool.ERASE_AREA) {
                ScrollingDocument.EraseMode.AREA
            } else {
                ScrollingDocument.EraseMode.SPOT
            }
        }
        toolbar.onPenSlot = { slot ->
            ink.penStyle = slot.style
            ink.penWidth = slot.width
            // Deliberately NO redraw. Every stroke records its own nib and weight, so a
            // slot change affects nothing already on the page — and a redraw here did real
            // harm: it threw away the engine's own pixels for every fresh stroke and
            // re-rendered them with TchRaster, which matches in average weight but not
            // pixel for pixel. On a 1-bit panel that swap is visible as ink "thickening"
            // the moment you opened the popup. The two renderers agree on weight; the fix
            // is to stop swapping one for the other when nothing needs repainting.
        }
        toolbar.onUndo = { doc?.undo() ?: ink.undo() }
        toolbar.onRedo = { doc?.redo() ?: ink.redo() }
        toolbar.onClear = {
            // With a lasso selection active, the trash deletes just that — deleting the
            // whole document while something is selected would be a nasty surprise.
            val d = doc
            if (d != null && d.deleteSelection() == 0) d.clearAll() else if (d == null) ink.clearAll()
        }
        // Manual de-ghost. Full refreshes are otherwise rationed (1-in-4 settles), so a
        // deliberate control matters after a long scrolling session.
        toolbar.onRefresh = { EinkRefresh.fullRefreshNow(ink) }
        toolbar.onExport = { exportPdf() }
        toolbar.onSettings = { anchor ->
            val d = doc
            if (d != null) {
                settingsPopup.show(anchor, d.backgroundStyle, d.backgroundSpacing)
            }
        }
        // Content tracks the finger during the drag.
        ink.onSwipe = { dy ->
            doc?.let { d -> d.scrollBy(dy); toolbar.setPageLabel(d.label()) }
        }
        // Fast binary waveform for the gesture, quality refresh once it stops.
        // Erase and lasso gestures need the same fast waveform scrolling uses: with the
        // engine off for those tools, nothing else is asking the panel to hurry.
        ink.onGestureInkPace = { active ->
            if (active) EinkRefresh.beginFast(ink) else EinkRefresh.scheduleSettle(ink)
        }
        ink.onScrollGesture = { active ->
            if (active) {
                EinkRefresh.beginFast(ink)
            } else {
                EinkRefresh.scheduleSettle(ink)
                doc?.commitScrollPosition()
            }
        }

        // Run once the host view is laid out: getPWInterFace() needs a real view, and the
        // engine sizes its writing bitmap from the host's measured size.
        ink.post {
            Log.i(TAG, "M-B: ink=${ink.width}x${ink.height} toolbar=${toolbar.height}px")
            caps = EngineCapabilities.probe(this, ink).also { r ->
                if (!r.usable) showEngineUnavailable(r)
            }
            // The fence that used to guard the strip below the canvas is gone with the
            // move. It was belt-and-braces even then — the ink host's own bounds already
            // exclude the toolbar — and its rect was expressed relative to the canvas, so
            // re-pointing it at the top would have fenced off the first 112 px of writable
            // canvas instead. Worth re-adding only if ink actually bleeds into the bar.

            // Continuous scrolling over an authored window (see ScrollingDocument).
            doc = ScrollingDocument(this, ink, ink.height, ink.width).also { d ->
                d.open()
                toolbar.setPageLabel(d.label())
            }
        }
    }

    /**
     * Fail loudly rather than presenting a notepad that silently will not write.
     *
     * There is deliberately no software fallback yet: an untested fallback is worse than
     * an honest error, and the previous software renderer is recoverable from git history
     * (commit 5856be7) if we decide to revive it.
     */
    private fun showEngineUnavailable(report: EngineCapabilities.Report) {
        val msg = buildString {
            append("Firmware ink engine unavailable.\n\n")
            append("Firmware: ${report.firmware}\n")
            append("Verified against: ${EngineCapabilities.VERIFIED_FIRMWARE}\n\n")
            if (report.missing.isNotEmpty()) {
                append("Missing:\n")
                report.missing.take(8).forEach { append("  - $it\n") }
            }
        }
        Log.e(TAG, "CAP: engine unusable\n$msg")
        addContentView(
            TextView(this).apply {
                text = msg
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
            },
            FrameLayout.LayoutParams(MATCH, MATCH)
        )
    }

    /**
     * Turn the page. The engine saves the outgoing page as part of `setLoadFilePath`, so
     * no explicit save is needed here — but a full refresh is worth spending on a page
     * turn, since it is the one moment a whole-screen change is expected and it clears
     * accumulated ghosting for free (SPEC.md §4.5).
     */
    private val pagesDir: File get() = File(filesDir, "pages")

    /** adb control channel, retained for measurement (SPEC.md §0.7). */
    private val toolReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (val cmd = intent?.getStringExtra("tool")) {
                "pwtest" -> {
                    ink.pwOverlayTest(intent.getIntExtra("v", 0))
                }
                // Arm the measured-vs-predicted ink width log (ScrollingDocument.measureInk),
                // then draw: each stroke reports what the engine drew against what
                // TchRaster predicts. `--ei w <tenths>` also sets the pen width, so the
                // port can be checked at several std widths without a rebuild.
                "penwidth" -> {
                    intent.getIntExtra("w", 0).takeIf { it > 0 }?.let { ink.penWidth = it / 10f }
                    doc?.measureInk = true
                    Log.i(TAG, "DIAG penwidth: measuring, std=${ink.penWidth}")
                }
                "pwclear" -> {
                    ink.penMode = NativeInkView.PenMode.PEN
                    ink.penMode = NativeInkView.PenMode.ERASE_AREA
                }
                "pen" -> { ink.setTool(0); ink.penMode = NativeInkView.PenMode.PEN; toolbar.select(InkToolbar.Tool.PEN) }
                "taperase" -> { ink.setTool(2); ink.penMode = NativeInkView.PenMode.ERASE_AREA; doc?.eraseMode = ScrollingDocument.EraseMode.AREA; toolbar.select(InkToolbar.Tool.ERASE_AREA) }
                "eraser" -> { ink.setTool(5); ink.penMode = NativeInkView.PenMode.ERASE_SPOT; doc?.eraseMode = ScrollingDocument.EraseMode.SPOT; toolbar.select(InkToolbar.Tool.ERASE_SPOT) }
                "clear" -> doc?.clearAll() ?: ink.clearAll()
                "undo" -> doc?.undo() ?: ink.undo()
                "redo" -> doc?.redo() ?: ink.redo()
                // Phase 0 spike controls
                "pause" -> ink.setEngineHandlesPen(false)
                "resume" -> ink.setEngineHandlesPen(true)
                "hide" -> ink.setInkLayerVisible(false)
                "show" -> ink.setInkLayerVisible(true)
                "pattern" -> ink.showTestPattern = !ink.showTestPattern
                "probe" -> com.superduper.notes.eink.EinkProbe.run(this@MainActivity)
                // Test harness (no pen required)
                "seed" -> doc?.let { d ->
                    d.seedStrokes(
                        intent.getIntExtra("n", 8),
                        intent.getIntExtra("gap", 120),
                        intent.getIntExtra("at", 200),
                    )
                    Log.i(TAG, "TEST: seeded -> ${d.strokeCount()} strokes bottom=${d.bottom()}")
                }
                "save" -> doc?.let { d -> d.save(); Log.i(TAG, "TEST: saved ${d.strokeCount()} strokes") }
                "scroll" -> doc?.let { d ->
                    d.scrollBy(intent.getIntExtra("dy", 200))
                    toolbar.setPageLabel(d.label())
                }
                "lassorect" -> doc?.let { d ->
                    val n = d.lassoRectWorld(
                        intent.getIntExtra("x0", 100).toFloat(), intent.getIntExtra("y0", 100).toFloat(),
                        intent.getIntExtra("x1", 1300).toFloat(), intent.getIntExtra("y1", 600).toFloat()
                    )
                    Log.i(TAG, "TEST: lasso selected=$n of ${d.strokeCount()}")
                }
                "penwidth" -> {
                    ink.penWidth = intent.getIntExtra("w", 25) / 10f
                    doc?.redraw()
                    Log.i(TAG, "TEST: penWidth=${ink.penWidth}")
                }
                "spoterase" -> doc?.let { d ->
                    val delta = d.spotEraseAtWorld(
                        intent.getIntExtra("x", 700).toFloat(),
                        intent.getIntExtra("y", 200).toFloat()
                    )
                    Log.i(TAG, "TEST: spotErase strokeDelta=$delta total=${d.strokeCount()}")
                }
                "delsel" -> doc?.let { d -> Log.i(TAG, "TEST: deleted=${d.deleteSelection()} remaining=${d.strokeCount()}") }
                "state" -> doc?.let { d ->
                    Log.i(TAG, "TEST: strokes=${d.strokeCount()} bottom=${d.bottom()} " +
                        "y=${d.scrollY} sel=${d.selectionCount()} canUndo=${d.canUndo} canRedo=${d.canRedo}")
                }
                "eraseat" -> doc?.let { d ->
                    val n = d.eraseAtWorld(
                        intent.getIntExtra("x", 700).toFloat(),
                        intent.getIntExtra("y", 200).toFloat()
                    )
                    Log.i(TAG, "TEST: eraseAt removed=$n remaining=${d.strokeCount()}")
                }
                "dump" -> ink.dumpStrokes()
                "methods" -> ink.dumpMethods(intent.getStringExtra("q"))
                "page" -> ink.setPagePath(
                    File(pagesDir, "${intent.getStringExtra("name") ?: "s0"}.tch").absolutePath
                )
                "files" -> Log.i(TAG, "FILES: " +
                    (pagesDir.listFiles()?.joinToString { "${it.name}=${it.length()}B" } ?: "(none)"))
                else -> Log.w(TAG, "TOOL: unknown '$cmd'")
            }
        }
    }

    /**
     * Export the document to a PDF the user places themselves.
     *
     * Uses the system "Save As" picker (Storage Access Framework) rather than the
     * ACTION_SEND share sheet — on this device the share sheet's chooser crashed
     * com.android.systemui outright (a window-reparenting exception in its stackdivider
     * component, unrelated to this app's code) before any target could be chosen. SAF also
     * fits the actual goal better: putting the file directly into a folder of the user's
     * choosing — including, if the vendor Notes app exposes one, wherever it watches —
     * rather than routing through whichever apps register as SEND targets.
     */
    /**
     * The full "SUPER DUPER" wordmark, shown briefly over the real content on cold launch.
     *
     * Added on top via [addContentView] rather than woven into [root] — the ink/engine
     * setup below is order-sensitive (SPEC.md §0.7) and this way it runs completely
     * unchanged; the splash is just another view sitting in front of it for a moment, then
     * removed. That does cost two extra full-panel e-ink transitions (one to show it, one
     * to reveal what's underneath) on every launch — a bounded, one-time cost the same as
     * any splash screen pays anywhere, not a recurring one.
     */
    private fun showSplash() {
        val splash = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val word = TextView(this).apply {
            text = "SUPER\nDUPER"
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
        }
        splash.addView(word, FrameLayout.LayoutParams(MATCH, MATCH))
        addContentView(splash, FrameLayout.LayoutParams(MATCH, MATCH))
        splash.postDelayed({ (splash.parent as? ViewGroup)?.removeView(splash) }, SPLASH_MS)
    }

    private fun exportPdf() {
        if (doc == null) return
        Log.i(TAG, "UI: export tapped")
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "SuperDuper.pdf")
            // Point the picker at /sdcard/Document — the Nomad's own general-document
            // folder (confirmed on device: it already holds imported PDFs/.docx, next to
            // Note/ which holds the vendor app's native .note files) — instead of its
            // default of Downloads, which Chauvet's own file browser doesn't surface
            // prominently. Standard SAF hint; the picker may ignore it on another device,
            // in which case it just falls back to its normal default.
            putExtra(
                android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                android.provider.DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", "primary:Document"
                )
            )
        }
        startActivityForResult(intent, REQUEST_EXPORT_PDF)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_PDF || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val d = doc ?: return
        // Rendering runs off the UI thread — a long document is many pages of vector redraw.
        Thread {
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use { d.exportPdf(it) } ?: error("no output stream for $uri")
            }.onFailure { Log.e(TAG, "EXPORT: failed", it) }.isSuccess
            runOnUiThread {
                android.widget.Toast.makeText(
                    this, if (ok) "Exported" else "Export failed", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    override fun onStart() {
        super.onStart()
        // Debug builds only. A runtime receiver on API 30 is implicitly exported, so in a
        // release build any installed app could broadcast tool=clear and wipe the
        // document — which the next autosave would then make permanent.
        if (BuildConfig.DEBUG) {
            registerReceiver(toolReceiver, IntentFilter("com.superduper.notes.TOOL"))
        }
    }

    override fun onStop() {
        super.onStop()
        if (BuildConfig.DEBUG) runCatching { unregisterReceiver(toolReceiver) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Order matters: cancel the pending settle before releasing bitmaps, or the
        // settle's invalidate() draws a recycled bitmap and crashes.
        EinkRefresh.cancelSettle()
        ink.dryLayer = null
        doc?.dispose()
    }

    override fun onPause() {
        super.onPause()
        // Sleep/wake goes through here, and the viewport is as important as the ink: if it
        // is lost, content sitting below the fold looks like it was never saved.
        doc?.commitScrollPosition()
        // Incremental auto-save holds back the newest 50 strokes, so without this
        // checkpoint recent work is lost on process death.
        doc?.save()
    }

    private companion object {
        const val TAG = "SuperDuper"
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val REQUEST_EXPORT_PDF = 1001
        const val SPLASH_MS = 900L
    }
}
