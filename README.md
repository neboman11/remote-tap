# RemoteTap

Press a button in an Android app from a second phone, over any network, relayed through a self-hosted [ntfy](https://ntfy.sh) server. No Firebase, no cloud accounts, no screen sharing.

**Use case:** Phone A is mounted somewhere (e.g. near a gate) running an app with a specific button. Phone B is in your pocket. Tap once on Phone B → Phone A presses the button.

## How it works

One APK installs on both phones. Phone A is the **Server** (has the app with the button). Phone B is the **Client** (the controller). Commands travel Phone B → ntfy → Phone A via persistent HTTP streaming. Phone A uses an Android Accessibility Service to tap the recorded button.

See [DESIGN.md](DESIGN.md) for full architecture details.

## Requirements

- Two Android phones (API 26+)
- A self-hosted ntfy server with an access token
- JDK 17 to build

## Build

```bash
# Requires JDK 17 — set JAVA_HOME if your default JVM is newer
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug
```

The same APK installs on both phones:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Setup

### Both phones

1. Open the app
2. Enter your ntfy server URL (e.g. `https://ntfy.example.com`) and an access token
3. Tap **Test & Save**
4. Choose a role: **Server** (Phone A) or **Client** (Phone B)

### Phone A — Server

1. Choose **Server**
2. Note the 6-character pairing code
3. Open **Accessibility Settings** and enable **RemoteTap Button Controller**
4. Tap **Record Target Button**
5. Select the target app from the list
6. Tap **Record button** — the app backgrounds itself and waits for you to switch to the target app
7. Once the target app is in the foreground, the overlay activates automatically — tap the button you want to control
8. The recorded button label or coordinates are shown in the status area

> **Tip:** Tap **Preview tap location** after recording to see a red square at the exact tap coordinates before testing remotely.

### Phone B — Client

1. Choose **Client**
2. Enter the pairing code from Phone A
3. Use the **Press Button** screen to trigger the remote tap

## Notes

**React Native apps** (and other non-native frameworks) don't expose accessibility node trees, so the app falls back to coordinate-based gesture dispatch. Recording and playback both work; you just see coordinates in the status instead of a button label.

**The target app must be installed on Phone A** — the press is simulated locally on that device.

**ntfy access control:** restrict the token to only `remotetap-*` topics for least-privilege access:

```bash
ntfy user add remotetap-user
ntfy access remotetap-user 'remotetap-*' rw
ntfy token add --user remotetap-user
```
