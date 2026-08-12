# Responsive UI audit — 2026-08-12

Scope: Android phone/tablet and web desktop/touch-tablet/phone. The audit used
the live UI-development relay and real Codex session data; it covered setup,
host inventory, saved sessions, active session, composer/slash discovery,
workspace folder selection, telemetry, and pending-dialog layout.

## Audited flow

1. **Relay setup and host inventory — healthy after fixes.** Android setup is
   readable at phone and landscape-tablet sizes. A connected phone now replaces
   the large credential form with a compact relay summary; the full form remains
   one tap away. Tablet retains the useful side-by-side setup/inventory layout.
   A successfully accepted token is normalized in live state as well as secure
   storage, so REST and subsequent WebSockets use the same credential.
2. **Host and saved-session navigation — healthy after fixes.** The phone uses
   a single-column drill-down and the tablet uses a stable master/detail split.
   A pending REST session-open is now cancelled and generation-guarded when the
   user navigates back or double-taps, preventing a hidden late WebSocket attach.
3. **Active-session header and controls — healthy after fixes.** The phone
   header no longer tries to fit Back, brand, status, model, effort, telemetry,
   and theme in one row. Model, effort, and permissions move to a labelled
   scrollable metadata rail below the cwd; tablet/desktop retain the compact
   top-bar pickers. No labels clip in the final captures.
4. **Composer and slash discovery — healthy after fixes.** Draft text survives
   Activity recreation, the editor grows to five lines and then scrolls, slash
   results are height-bounded, `/collab` is discoverable on Android, and image /
   queued-turn removal controls have 44dp targets. On a web phone `/plan` and
   `/goal` appear first in the rail instead of being hidden beyond three wide
   picker chips. Audited touch layouts contain no sub-44px interactive target.
5. **Folder and workspace navigation — healthy after fixes.** The web phone
   picker is exactly 390×844, has no horizontal escape, and scrolls internally
   instead of expanding to the full folder-list height. Android publishes a new
   path only after its directory listing succeeds, so a failure cannot display
   stale entries under the wrong path.
6. **Telemetry and auxiliary surfaces — healthy after fixes.** Landscape
   tablets at 960dp and wider retain a permanent telemetry pane; narrower
   tablets use the existing sheet. Web touch tablets and phones now receive
   44px controls through pointer capability rather than a phone-width-only
   media query. Phone bottom sheets, their drag handle, and drawer actions all
   passed the touch-target sweep.
7. **Approval and user-input dialogs — healthy after fixes.** Long prompt bodies
   now have bounded, scrollable content and user-input progress/notes survive
   Activity recreation, reducing rotation and small-height clipping risk.

## Verification evidence

- Web: 1440×900 mouse desktop, 900×1100 touch tablet, and 390×844 touch phone.
  Final page width equalled viewport width in every state. Tablet and phone had
  zero undersized visible controls in dashboard, session, slash, and telemetry
  states. The only off-viewport mobile node is an intentionally scrollable
  configuration chip after the visible plan/goal controls.
- Web folder picker: modal bounds `0,0 → 390,844`, document width 390, no
  out-of-viewport edge, and zero undersized controls.
- Android: `medium_phone` (1080×2400, 420 dpi) and `medium_tablet`
  (2560×1600, 320 dpi) were installed and visually inspected from fresh APK
  builds. The connected suite passed 15/15 tests on each emulator.
- Automated: web ESLint, 17 Vitest files / 106 tests, and production Vite build;
  Android debug+release JVM tests, lint, two-device instrumentation, and
  `android/build.sh` all passed.
- Final debug APK SHA-256:
  `0d14fe109feed8bef496fa7ef3c3fdd9f0c0bdeb80f291192b028867aa0047f2`.
- Capture directories for this run:
  `/tmp/remotex-web-audit-20260812` and
  `/tmp/remotex-android-audit-20260812/final`.

## Deliberately left open

The remaining responsive-adjacent gaps are tracked as `I-029` through `I-032`:
active-turn close confirmation on Android, telemetry freshness recomposition,
Android cwd refresh after `/cd`, and durable responsive-browser CI. They do not
invalidate the layouts verified above.
