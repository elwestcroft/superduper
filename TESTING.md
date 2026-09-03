# Test plan — things only a human with a pen can check

Everything downstream of stroke capture is covered by the automated harness (see
"Automated coverage" at the end). What is **not** covered is anything requiring a real
EMR pen: Chauvet denies `INJECT_EVENTS` to the shell, so pen input cannot be simulated.

Work top to bottom. Each item says what to do, what should happen, and why it matters.

---

## 1. Ink — the fundamentals

| # | Do | Expect |
|---|---|---|
| 1.1 | Write a few words normally | Ink appears with no perceptible lag. This is the firmware engine; it should feel like the stock Notes app |
| 1.2 | Write, then lift the pen and watch the stroke | **The stroke must not change appearance on pen-up.** It is handed off from the engine's wet ink to our rendering. Any thickness/shape jump means the per-point width fix is off |
| 1.3 | Write pressing hard, then very light | Thick and thin should both work, varying *within* a stroke |
| 1.4 | Rest your palm on the screen while writing | No stray marks, no page movement |
| 1.5 | Write near the very bottom edge, above the toolbar | Ink should stop at the canvas edge and never draw on the toolbar |
| 1.6 | Tap a toolbar button with the pen | Button activates; no ink is left on it |

## 2. Scrolling

| # | Do | Expect |
|---|---|---|
| 2.1 | Write several lines, then swipe up slowly with one finger | Content tracks your finger as it moves — not a jump on release |
| 2.2 | Short flick vs long drag | Distance scrolled matches distance swiped |
| 2.3 | Swipe up, then immediately swipe again | **No black flash between the two.** The settle is debounced 500 ms; a flash here means it is firing too early |
| 2.4 | Scroll, stop, wait ~1 s | One clean refresh after you stop is expected and correct |
| 2.5 | Scroll down past all content | Stops at the last ink; you cannot fall into empty space |
| 2.6 | Scroll back to the top | Original content intact, nothing shifted |
| 2.7 | **Look closely at ink after scrolling** | No thin white horizontal slices through strokes. That was a real bug (band-overhang); confirm it is gone |
| 2.8 | Start a stroke, and while the pen is down try to swipe with a finger | Page must **not** move. A mid-stroke scroll would shear the stroke |
| 2.9 | Scroll for a while without stopping | Page will get ghosty — expected. Tap the ⟳ refresh icon; it should clean up |

## 3. Erase

| # | Do | Expect |
|---|---|---|
| 3.1 | Write 3 separate strokes. Select **Erase stroke**, tap one | That whole stroke disappears; the others are untouched |
| 3.2 | Select **Erase area**, scrub across two strokes | Both disappear entirely (currently whole-stroke, not split — see Known gaps) |
| 3.3 | Erase, then scroll away and back | Erased ink stays erased |
| 3.4 | Erase something, then tap **undo** | It comes back |
| 3.5 | Erase near the edge of a stroke without touching it | Nothing is deleted — the eraser reach is 18 px; tell me if that feels too grabby or too fussy |

## 4. Undo / redo

| # | Do | Expect |
|---|---|---|
| 4.1 | Draw 3 strokes, undo 3 times | They disappear one at a time, newest first |
| 4.2 | Redo 3 times | They come back in order |
| 4.3 | Undo twice, then draw something new | Redo becomes unavailable (the redo branch is discarded — standard) |
| 4.4 | Scrub-erase across 5 strokes, then undo **once** | All 5 return. One gesture is one undo entry |
| 4.5 | Tap **clear** (trash), then undo | Everything returns |

## 4b. Lasso

| # | Do | Expect |
|---|---|---|
| 4b.1 | Write several strokes. Select **lasso**, draw a loop around two of them | A dashed box appears around just those two |
| 4b.2 | Tap the trash | Only the selected strokes are deleted; the rest survive |
| 4b.3 | Undo | They come back |
| 4b.4 | Lasso a loop that crosses itself | Selection should still be sensible — a winding-number test is used precisely for this |
| 4b.5 | Lasso a region, then scroll | The dashed box scrolls with the content |
| 4b.6 | Lasso loosely around a long stroke that extends outside the loop | It should NOT be selected — 80% of a stroke's points must be enclosed. Tell me if that threshold feels wrong |

## 5. Persistence — the one to be most suspicious of

| # | Do | Expect |
|---|---|---|
| 5.1 | Write something, wait ~5 s, then swipe the app away | — |
| 5.2 | Reopen it | Everything is there, at the same scroll position |
| 5.3 | Write something and **immediately** kill the app (under 3 s) | The last few seconds may be lost. Autosave is debounced 3 s — tell me if that window feels too risky |
| 5.4 | Write, scroll far down, write more, restart | Both areas present, positioned correctly |
| 5.5 | Fill several screens, restart, scroll through all of it | Nothing missing or displaced |

## 6. Toolbar and UI

| # | Do | Expect |
|---|---|---|
| 6.1 | Look at the toolbar | All 8 icons visible, nothing cropped at the right edge (this was the reported bug) |
| 6.2 | Tap each tool | Selected tool inverts to black; only one at a time |
| 6.3 | Are the icons legible at a glance? | They are drawn as line art specifically for this panel — tell me which ones read badly |
| 6.4 | Position readout on the right | Updates as you scroll |

## 7. Long-session soak (whenever you have time)

Use it for real for 20–30 minutes: write, scroll, erase, undo. Watch for ghosting
build-up, slowdown as the document grows, anything that feels wrong. This is the test the
automation cannot approximate.

---

## Known gaps — do not report these as bugs

- **Lasso moves nothing.** Select and delete work; dragging a selection to move it does not.
- **Erase area does not split strokes.** It deletes whole strokes, like tap-erase. Splitting is designed (SPEC §6.4) but unbuilt.
- **No zoom.** The engine records at 1:1 and cannot ink scaled.
- **No layers.** The document is flat.
- **Undo history does not survive a restart.** The document does; the history does not.
- **Ink rendering is ours, not Ratta's.** Committed strokes use our rasterizer, so they will not look pixel-identical to the engine's wet ink. If the difference is obvious at pen-up, that is worth reporting.

## Automated coverage (already passing, no need to retest)

Seed/render, erase, undo, redo, clear, undo-clear, scroll, deep document at y=67,630,
save/restart round-trip, force-stop survival. 9-case regression, 0 fatal exceptions.
Scroll 11–20 ms per step with 200 strokes; erase 4 ms with 200 strokes.

Drive it yourself with:
```
adb shell am broadcast -a com.superduper.notes.TOOL --es tool seed --ei n 20
adb shell am broadcast -a com.superduper.notes.TOOL --es tool state
adb shell am broadcast -a com.superduper.notes.TOOL --es tool scroll --ei dy 400
```
