# SuperDuper

An infinite-vertical-canvas note app for the **Supernote Nomad (A6 X2)** — Chauvet OS,
Android 11, sideloaded via adb. No store, no GMS.

Native-latency ink, because it uses the device's own ink engine. Continuous scrolling,
because the app owns the canvas rather than asking that engine to be something it isn't.

## Get the app

Grab the latest signed APK from the **[Releases](../../releases)** page and sideload it —
no build step needed. On the Nomad: **Settings > Security & Privacy > Sideloading > ON**,
then install the APK however you'd normally sideload one.

## Start here (for building from source)

- **[TESTING.md](TESTING.md)** — what to check by hand, and the known gaps

## How it works

The firmware ink engine (`htfyun.penwrite.ctrl.PWCoreCtrl`, reached through
`View.getPWInterFace()`) draws **only the live stroke**. Everything else is ours:

| | Owner |
|---|---|
| Wet stroke | Firmware engine — native latency, vendor pen algorithms |
| Persisted ink, scroll, viewport | App — a viewport bitmap painted beneath the engine's overlay |
| Erase, undo, persistence | App |

Scrolling pauses the ink layer, blit-shifts the viewport bitmap, rasterises only the newly
exposed band, and resumes — so cost tracks how far you moved, not screen area. The panel
runs a fast binary waveform during the gesture and settles to quality when you stop.

That split is not arbitrary: it is what the working e-ink infinite-canvas apps do
([Notable](https://github.com/Ethran/notable), [Notate](https://github.com/alexdremov/notate)).
The first attempt here did the reverse — the engine held the document and its contents were
swapped on every scroll — and it could not be made to work.

## Build and install

The toolchain is already set up on the dev machine (SPEC §2.4 has versions and paths):

```bash
./deploy.sh          # build, install, launch
./deploy.sh -l       # ...and tail the log
```

One-time on the device: **Settings → Security & Privacy → Sideloading → ON**.

## Debug controls

Debug builds expose an adb channel (guarded by `BuildConfig.DEBUG` — a runtime receiver on
API 30 is implicitly exported, so it must not exist in a release build):

```bash
adb shell am broadcast -a com.superduper.notes.TOOL --es tool seed --ei n 20
adb shell am broadcast -a com.superduper.notes.TOOL --es tool scroll --ei dy 400
adb shell am broadcast -a com.superduper.notes.TOOL --es tool state
```

`seed` exists because Chauvet denies `INJECT_EVENTS` to the shell — pen input cannot be
simulated, so strokes are injected into the model directly to test everything downstream.

Logs: `adb logcat -s SuperDuper:V`
