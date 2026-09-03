package com.superduper.notes.history

import android.util.Log

/**
 * Undo/redo over the app's own document (SPEC.md §7).
 *
 * The firmware engine has its own undo — vector, non-destructive, and genuinely good — but
 * it only knows about strokes the engine recorded, and the engine's buffer is cleared after
 * every pen-up now that the app owns the document. So this is ours to keep.
 *
 * Commands hold references to immutable stroke objects rather than copies of pixels or
 * point arrays, so the cost of a history entry is the size of the reference list, not the
 * ink. That is what makes it affordable to keep a deep stack on a 4 GB device.
 */
interface Command {
    /** Short description for logs. */
    val label: String
    fun apply()
    fun revert()
    /** Rough retained size in bytes, for bounding the stack. */
    val weight: Int
}

class CommandStack(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxBytes: Int = MAX_BYTES,
) {
    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Record an already-applied command.
     *
     * The caller performs the action and then reports it, rather than the stack applying
     * it: the action has usually already happened as a side effect of drawing, and
     * re-applying it here would double it.
     */
    fun record(command: Command) {
        undoStack.addLast(command)
        // A new action invalidates the redo branch — standard, and it avoids the
        // ambiguity of a tree-shaped history.
        redoStack.clear()
        trim()
    }

    fun undo(): Command? {
        val c = undoStack.removeLastOrNull() ?: return null
        c.revert()
        redoStack.addLast(c)
        Log.i(TAG, "HISTORY: undo ${c.label} (${undoStack.size} left)")
        return c
    }

    fun redo(): Command? {
        val c = redoStack.removeLastOrNull() ?: return null
        c.apply()
        undoStack.addLast(c)
        Log.i(TAG, "HISTORY: redo ${c.label} (${redoStack.size} left)")
        return c
    }

    fun clear() {
        undoStack.clear(); redoStack.clear()
    }

    /**
     * Bound the stack by both count and retained bytes.
     *
     * Bytes matter because an erase command keeps the strokes it removed alive — they are
     * no longer in the document, so the history is the only thing referencing them. A
     * count-only cap would let a few large erases pin an unbounded amount of ink.
     */
    private fun trim() {
        while (undoStack.size > maxEntries) undoStack.removeFirst()
        var bytes = undoStack.sumOf { it.weight }
        while (bytes > maxBytes && undoStack.size > 1) {
            bytes -= undoStack.removeFirst().weight
        }
    }

    private companion object {
        const val TAG = "SuperDuper"
        const val MAX_ENTRIES = 100
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
