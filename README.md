# Screen Share

An Android app that shares your device screen to a **web server** and lets you watch
it live in a browser.

- **Android app** (`app/`): captures the screen with `MediaProjection`, encodes each
  frame as JPEG, and pushes it over a **WebSocket** to the server. Configurable URL,
  foreground service, and a small live preview.
- **Web server** (`server/`): a Node.js WebSocket relay with a browser viewer page
  that renders the incoming frames live.

## How it works

```
Android phone ──WS JPEG frames──▶ server (/stream) ──broadcast──▶ browsers (/watch)
```

Protocol: the app opens a WebSocket to `<ws://host:8765>/stream`, first sends a JSON
`{type:"stream", width, height, device}` message, then sends each frame as a binary
JPEG. On disconnect it sends `{type:"stop"}`.

## Run the server

Requires Node.js 18+.

```bash
cd server
npm install
npm start          # viewer at http://localhost:3000, relay on ws://localhost:8765
```

Open `http://localhost:3000` in a browser to watch. You can point the viewer at a
different relay with `?ws=PORT`.

## Build / install the Android app

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

1. Install the APK on a device/emulator (Android 7.0+, API 24).
2. Set the **WebSocket Server URL** (default `ws://10.0.2.2:8765/stream` for the
   Android emulator; use your machine's LAN IP for a real device, e.g.
   `ws://192.168.1.20:8765/stream`).
3. Tap **Start Sharing** and grant screen-capture permission.

## CI

`.github/workflows/android-build.yml` builds the debug APK on every push and uploads
it as a workflow artifact.