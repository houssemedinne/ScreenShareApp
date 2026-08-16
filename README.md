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

HTTP (viewer page) and WebSocket (relay) share **one port** (`$PORT`, default 3000) —
the way Railway and other PaaS providers expose a single port.

Protocol: the app opens a WebSocket to `<ws://host>/stream` sharing the port, first
sends a JSON `{type:"stream", width, height, device}` message, then sends each frame
as a binary JPEG. On disconnect it sends `{type:"stop"}`.

### Multiple simultaneous streams
- Every `/stream` connection is its own stream, auto-registered with a unique `id`.
  The server replies `{type:"stream-ready", id}` and lists it from the device name
  in the hello message, so **many users can share at once**.
- Viewers watch a specific stream via `/watch?stream=<id>` (or connect without
  `?stream=` to auto-follow the most recent one).
- `GET /api/streams` returns the live list: `[{id, device, width, height, viewers}]`.
- The viewer page shows a dropdown; pick a stream or use **auto (latest)**.

## Run the server

Requires Node.js 18+.

```bash
cd server
npm install
npm start          # viewer at http://localhost:3000, relay on ws://localhost:3000
```

Open `http://localhost:3000` in a browser to watch.

## Deploy to Railway (or any PaaS)

- Host: use `server/` (with `npm start`) as the service start command.
- The server binds to the `$PORT` Railway provides, so both the viewer page and the
  WebSocket relay are reachable on your public `https://<app>.up.railway.app`.
- The viewer works automatically at that URL (`wss://<host>/watch`). You can use
  `?ws=PORT` to point the viewer at a different relay port.

## Build / install the Android app

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

1. Install the APK on a device/emulator (Android 7.0+, API 24).
2. Set the **WebSocket Server URL**:
   - Emulator → `ws://10.0.2.2:3000/stream` (host machine).
   - Real device, server on your LAN → `ws://192.168.1.20:3000/stream`.
   - Railway (HTTPS) → `wss://<app>.up.railway.app/stream`. Because HTTPS is used,
     use `wss://` and the default port 443 (no port number).
3. Tap **Start Sharing** and grant screen-capture permission.

## CI

`.github/workflows/android-build.yml` builds the debug APK on every push and uploads
it as a workflow artifact.