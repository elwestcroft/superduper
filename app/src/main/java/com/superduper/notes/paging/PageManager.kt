package com.superduper.notes.paging

import android.content.Context
import android.util.Log
import com.superduper.notes.eink.NativeInkView
import java.io.File

/**
 * The page ribbon (SPEC.md §0.6 M-C).
 *
 * M-A killed the multi-screen segment: the engine's native path renders garbage into a
 * bitmap larger than the screen, so a page is exactly one screen. The infinite canvas is
 * therefore a *ribbon* of screen-height pages, and "scrolling" is a page swap.
 *
 * That is only tolerable because the swap is cheap: measured at ~100 ms cold for a 22 KB
 * page, which is entirely hidden inside the e-ink waveform (~120–300 ms). The panel is
 * slower than the paging, so the seam is invisible.
 *
 * Ownership split: the engine owns each page's ink (strokes, erasers, undo, and the
 * `.tch`/`.png` file pair); this class owns which page is current, where it sits in the
 * document (`baseY`), and keeping neighbours warm.
 */
class PageManager(
    private val context: Context,
    private val ink: NativeInkView,
    private val pageHeightPx: Int,
) {

    private val prefs = context.getSharedPreferences("pages", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "pages").apply { mkdirs() }

    /** Current page index. Page 0 is the top of the document; indices grow downward. */
    var index: Int = prefs.getInt(KEY_INDEX, 0)
        private set

    /** Document-space Y of the current page's top edge. */
    val baseY: Int get() = index * pageHeightPx

    /** Highest page index that has ever been written to — the document's extent. */
    var maxIndex: Int = prefs.getInt(KEY_MAX, 0)
        private set

    private fun fileFor(i: Int) = File(dir, "s$i.tch")

    /** Bind the engine to the current page. Call once the engine is live. */
    fun open() {
        ink.setPagePath(fileFor(index).absolutePath)
        preloadNeighbours()
        Log.i(TAG, "PAGE: opened index=$index baseY=$baseY max=$maxIndex")
    }

    fun next(): Boolean = goTo(index + 1)

    fun previous(): Boolean = if (index == 0) {
        Log.i(TAG, "PAGE: already at the top of the document"); false
    } else {
        goTo(index - 1)
    }

    /**
     * Switch pages. `setLoadFilePath` saves the outgoing page itself
     * (`doSetLoadFilePath` → `doSaveAll`) before loading the new one, so there is no
     * separate save step — but we still checkpoint explicitly on lifecycle events,
     * because incremental auto-save holds back the newest 50 strokes.
     */
    fun goTo(target: Int): Boolean {
        if (target < 0 || target == index) return false
        val t0 = android.os.SystemClock.uptimeMillis()
        index = target
        if (target > maxIndex) maxIndex = target
        prefs.edit().putInt(KEY_INDEX, index).putInt(KEY_MAX, maxIndex).apply()

        ink.setPagePath(fileFor(index).absolutePath)
        preloadNeighbours()
        Log.i(TAG, "PAGE: -> index=$index baseY=$baseY max=$maxIndex " +
            "swapCallMs=${android.os.SystemClock.uptimeMillis() - t0}")
        return true
    }

    /**
     * Warm the pages either side so a swap is I/O-free. The engine keeps an ~80 MB
     * multi-page cache, so neighbours stay resident.
     */
    private fun preloadNeighbours() {
        listOf(index - 1, index + 1)
            .filter { it >= 0 && fileFor(it).exists() }
            .forEach { ink.preloadPage(fileFor(it).absolutePath) }
    }

    /** Explicit checkpoint — the newest 50 strokes are not covered by auto-save. */
    fun checkpoint() {
        ink.savePage(2000)
    }

    /** Human-readable position, e.g. "3 / 7". Pages are 1-based for display. */
    fun label(): String = "${index + 1} / ${maxIndex + 1}"

    private companion object {
        const val TAG = "SuperDuper"
        const val KEY_INDEX = "index"
        const val KEY_MAX = "max"
    }
}
