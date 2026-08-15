'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

const HTTP_PORT = Number(process.env.HTTP_PORT || 3000);
const WS_PORT = Number(process.env.WS_PORT || 8765);
const PUBLIC_DIR = path.join(__dirname, 'public');

// ---------------------------------------------------------------------------
// HTTP server: serves the browser viewer page.
// ---------------------------------------------------------------------------
const httpServer = http.createServer((req, res) => {
  const pathname = req.url.split('?')[0];
  const file = pathname === '/' ? '/index.html' : pathname;
  const full = path.normalize(path.join(PUBLIC_DIR, file));
  if (!full.startsWith(PUBLIC_DIR)) {
    res.writeHead(403).end('Forbidden');
    return;
  }
  fs.readFile(full, (err, data) => {
    if (err) {
      res.writeHead(404).end('Not found');
      return;
    }
    const ext = path.extname(full);
    const type = ext === '.html' ? 'text/html' : 'application/octet-stream';
    res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store' });
    res.end(data);
  });
});
httpServer.listen(HTTP_PORT, () => {
  console.log(`Viewer page  -> http://localhost:${HTTP_PORT}`);
});

// ---------------------------------------------------------------------------
// WebSocket relay:
//   /stream  - one publisher (the Android app) sends JPEG frames
//   /watch   - any number of viewers receive those frames live
// ---------------------------------------------------------------------------
const wss = new WebSocketServer({ port: WS_PORT });
const watchers = new Set();
let latestFrame = null;

function broadcast(buffer) {
  for (const socket of watchers) {
    if (socket.readyState === socket.OPEN) {
      try {
        socket.send(buffer);
      } catch (err) {
        /* keep going */
      }
    }
  }
}

wss.on('connection', (socket, req) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const route = url.pathname;

  if (route === '/stream') {
    console.log('[publisher] connected');
    socket.on('message', (data, isBinary) => {
      if (isBinary) {
        latestFrame = data;
        broadcast(data);
      } else {
        const text = String(data);
        try {
          const msg = JSON.parse(text);
          if (msg.type === 'stream') {
            console.log(
              `[publisher] stream started: ${msg.device} ${msg.width}x${msg.height}`
            );
          } else if (msg.type === 'stop') {
            console.log('[publisher] stream stopped');
          }
        } catch (_) {
          /* ignore malformed text */
        }
      }
    });
    socket.on('close', () => console.log('[publisher] disconnected'));
  } else if (route === '/watch') {
    console.log(`[viewer] connected (${watchers.size + 1} total)`);
    watchers.add(socket);
    if (latestFrame && socket.readyState === socket.OPEN) {
      try {
        socket.send(latestFrame);
      } catch (_) {
        /* keep going */
      }
    }
    socket.on('close', () => {
      watchers.delete(socket);
      console.log(`[viewer] disconnected (${watchers.size} remaining)`);
    });
  } else {
    socket.close(1008, 'Unknown path');
  }
});

console.log(
  `WebSocket relay -> ws://localhost:${WS_PORT}  [/stream publisher | /watch viewer]`
);