# RemoteTap — Design Document

## Problem

Phone A has an app with a specific button. That app cannot run on Phone B. The goal is to press that button from Phone B, over any network (same WiFi or separate mobile data connections), without using generic screen-sharing tools.

---

## Architecture Overview

```
Phone B (Client)                  ntfy (self-hosted)            Phone A (Server)
─────────────────                 ──────────────────            ─────────────────────────
ClientActivity                    ntfy.nesbitt.rocks            CommandListenerService
  │                                                               │
  ├─ tap "Press Button"    ──► POST /remotetap-{code}-cmd  ───► observeIncomingCommands()
  │                                                               │  (streaming JSON GET)
  │                                                               │
  └─ wait for result       ◄── POST /remotetap-{code}-result ◄── acknowledgeCommand()
       (streaming JSON GET)
```

One APK is installed on both phones. On first launch, the user configures their ntfy server, then chooses a role: **Server** (Phone A, the phone with the app) or **Client** (Phone B, the controller).

---

## Relay: Self-hosted ntfy

The project uses [ntfy](https://ntfy.sh) as the message relay — no Firebase, no Google services, no third-party cloud.

- **Publish** (client→server): HTTP POST to `https://ntfy.nesbitt.rocks/{topic}`
- **Subscribe** (server listening): streaming HTTP GET to `https://ntfy.nesbitt.rocks/{topic}/json`
  — ntfy streams newline-delimited JSON events on this endpoint, one per message, indefinitely.

### Topics

| Topic | Direction | Purpose |
|---|---|---|
| `remotetap-{pairingCode}-cmd` | Client → Server | Carry command payload |
| `remotetap-{pairingCode}-result` | Server → Client | Carry result/ack payload |

Topics are namespaced with `remotetap-` to avoid collision with other ntfy usage.

### Authentication

Your ntfy instance has `NTFY_AUTH_DEFAULT_ACCESS: deny-all`. Both phones authenticate using a personal access token generated in the ntfy web UI or CLI:

```bash
ntfy token add remotetap
```

The token is entered once in the app's ntfy setup screen. It's stored in Android SharedPreferences. Both phones use the same token (or two separate tokens if you prefer to limit scope per-device).

### ntfy Access Control (optional, tighter security)

You can create a dedicated ntfy user and restrict it to only the `remotetap-*` topic pattern:

```bash
ntfy user add remotetap-user
ntfy access remotetap-user 'remotetap-*' rw
```

Then generate a token for that user instead of using your admin token.

---

## Pairing

1. Phone A (Server) generates a random 6-character code from an unambiguous character set (`ABCDEFGHJKLMNPQRSTUVWXYZ23456789` — no 0/O, 1/I/L).
2. Phone B (Client) enters that code.
3. Both phones derive the ntfy topic names from the code.
4. No account or login required beyond the shared ntfy access token. The pairing code acts as a topic namespace.

---

## Communication Flow

### Sending a command (Client)

```
1. Record sinceMs = System.currentTimeMillis()
2. HTTP POST to /remotetap-{code}-cmd
   Body: { "id": "<uuid>", "type": "PRESS", "timestampMs": ... }
   Header: Authorization: Bearer <token>
3. Open streaming GET to /remotetap-{code}-result/json?since=<sinceMs/1000 - 5>
   (the ?since= param ensures any result cached before we subscribed is still delivered)
4. Wait up to 10 seconds for a result event with matching commandId
5. Show success or error
```

### Receiving and executing a command (Server)

```
CommandListenerService (foreground service, always running):
1. Streaming GET to /remotetap-{code}-cmd/json (indefinite)
2. For each "message" event, parse body as Command
3. Call RemoteTapAccessibilityService.pressRecordedButton()
4. HTTP POST to /remotetap-{code}-result
   Body: { "commandId": "<uuid>", "success": true/false, "errorMessage": "..." }
5. If connection drops, reconnect with exponential backoff (1s → 2s → 4s … 30s max)
```

---

## Button Recording (Server / Phone A)

The core technical challenge: how does the app know which button to press in another app?

**Solution: Android Accessibility Service + one-time tap recording**

### Setup flow

1. User grants Accessibility Service permission to RemoteTap in Android Settings.
2. User opens the target app and navigates to the screen with the target button.
3. User opens RemoteTap → "Record Target Button".
4. User taps "Start recording" — a transparent full-screen overlay is drawn using `WindowManager` at type `TYPE_ACCESSIBILITY_OVERLAY`.
5. The next touch on the overlay is intercepted. The `(x, y)` coordinates are passed to `RemoteTapAccessibilityService.captureNodeAtPoint()`.
6. The accessibility service walks the active window's node tree to find the clickable node at that point. It records:
   - `viewIdResourceName` (e.g., `com.example.app:id/submit_btn`)
   - `text` (visible label)
   - `contentDescription` (accessibility label)
   - `boundsInScreen` (pixel coordinates as fallback)
   - `className` and `packageName`
7. This `ButtonConfig` is saved to `SharedPreferences` (JSON via Gson).

### Press flow (when a command arrives)

1. `RemoteTapAccessibilityService.pressRecordedButton()` is called.
2. Loads `ButtonConfig` from prefs.
3. Tries `findAccessibilityNodeInfosByViewId()` first (most reliable), then `findAccessibilityNodeInfosByText()`.
4. If a node is found: `node.performAction(ACTION_CLICK)` — works even if the screen is off.
5. If no node is found: falls back to `dispatchGesture()` at the recorded coordinates (requires the target app to be in the foreground).

---

## Reliability

| Scenario | Behavior |
|---|---|
| Phone A screen is off | ACTION_CLICK works; gesture fallback requires screen on |
| Target app not in foreground | ACTION_CLICK still works if app is running in background; gesture fails |
| ntfy offline / phone offline | Commands queued on client side fail immediately; server reconnects with backoff |
| Connection drop on server | Reconnect loop in CommandListenerService with exponential backoff (max 30s) |
| Button moved (app update) | viewId match fails → text/contentDescription match → coordinate fallback |
| Stale result on client | `?since=` param ensures results cached up to 5s before subscription are included |

---

## Service Lifecycle (Server / Phone A)

- `CommandListenerService` is a foreground service with a persistent notification so Android does not kill it.
- `BootReceiver` restarts the service on device reboot if the server role is configured.
- The accessibility service runs separately and is managed by Android's accessibility framework.

---

## First Launch Flow

```
Both phones:
  1. NtfySetupActivity  — enter server URL + access token, test connection
  2. MainActivity        — choose role: Server or Client

Phone A (Server):
  3. PairingActivity     — displays generated 6-char pairing code
  4. ServerActivity      — enable accessibility, record target button, start listener

Phone B (Client):
  3. PairingActivity     — enter the code from Phone A
  4. ClientActivity      — single "Press Button" screen
```

---

## File Structure

```
remote-tap/
├── DESIGN.md                                ← this document
└── app/src/main/
    ├── AndroidManifest.xml
    ├── kotlin/com/remotetap/
    │   ├── model/
    │   │   ├── ButtonConfig.kt              ← recorded button node data
    │   │   └── Command.kt                   ← command + result data classes
    │   ├── repository/
    │   │   ├── PreferencesRepository.kt     ← local storage (role, code, ntfy config, button)
    │   │   └── CommandRepository.kt         ← ntfy publish/subscribe via OkHttp
    │   ├── service/
    │   │   ├── RemoteTapAccessibilityService.kt  ← node capture + ACTION_CLICK
    │   │   ├── CommandListenerService.kt         ← foreground service, ntfy stream listener
    │   │   └── BootReceiver.kt                   ← restart on boot
    │   └── ui/
    │       ├── MainActivity.kt              ← ntfy gate + role selection / routing
    │       ├── NtfySetupActivity.kt         ← server URL + token entry, connection test
    │       ├── PairingActivity.kt           ← code display (server) / code entry (client)
    │       ├── ServerActivity.kt            ← status + setup screen for Phone A
    │       ├── ClientActivity.kt            ← "Press Button" screen for Phone B
    │       └── ButtonRecordingActivity.kt   ← guides recording the target button
    └── res/
        ├── layout/                          ← one XML per Activity
        ├── values/                          ← strings, colors, themes
        └── xml/
            └── accessibility_service_config.xml
```

---

## Known Gaps / TODO

### High priority (needed for MVP)

1. **Recording overlay (`ButtonRecordingActivity.showRecordingOverlay()`):** Stubbed. Implementation:
   ```kotlin
   // Must be called from AccessibilityService context
   val wm = getSystemService(WindowManager::class.java)
   val params = WindowManager.LayoutParams(
       MATCH_PARENT, MATCH_PARENT,
       WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
       WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
       PixelFormat.TRANSLUCENT
   )
   val overlay = View(context)
   overlay.setOnTouchListener { _, event ->
       val config = captureNodeAtPoint(event.rawX, event.rawY)
       prefs.buttonConfig = config
       wm.removeView(overlay)
       true
   }
   wm.addView(overlay, params)
   ```
   The overlay must be added from the accessibility service (not an Activity) since `TYPE_ACCESSIBILITY_OVERLAY` requires the service context. Wire this up via a broadcast or a shared singleton.

2. **Notification icon (`ic_notification`):** Referenced in `CommandListenerService` but not created. Add `res/drawable/ic_notification.xml` as a simple vector.

### Medium priority

3. **Stale command filtering:** If Phone A was offline and commands queued in ntfy (ntfy caches for 12h by default), they'll all fire on reconnect. Add a max-age check: discard commands where `System.currentTimeMillis() - command.timestampMs > 30_000`.

4. **Multiple buttons:** Currently one button per device. Could extend to a named list of buttons, with the client showing a list of buttons to choose from.

### Low priority / future

5. **Local-only mode:** When both phones are on the same WiFi, a direct WebSocket (Ktor) with mDNS discovery would have lower latency and no internet dependency. ntfy path still works as fallback.

6. **Per-device ntfy tokens:** Use separate tokens per phone and create a dedicated ntfy user scoped to `remotetap-*` topics for minimal-privilege access.

---

## Build & Install

```bash
# Prerequisites: Android Studio or command-line SDK tools, JDK 17+
# No google-services.json or Firebase setup required.

./gradlew assembleDebug

# Install on both phones (same APK)
adb -s <phone_a_serial> install app/build/outputs/apk/debug/app-debug.apk
adb -s <phone_b_serial> install app/build/outputs/apk/debug/app-debug.apk
```

On first launch on each phone:
1. Enter `https://ntfy.nesbitt.rocks` as the server URL
2. Enter your ntfy access token (generate one at `https://ntfy.nesbitt.rocks/account`)
3. Tap "Test & Save"
4. Choose your role and follow the setup steps
