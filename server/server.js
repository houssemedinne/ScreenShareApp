'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

// Railway / PaaS exposes a single public port via $PORT. HTTP and WebSocket
// SHARE that one port, so the relay is reachable on the same URL as the viewer
// page. No extra firewall port needs to be opened.
const PORT = Number(process.env.PORT || 3000);
const PUBLIC_DIR = path.join(__dirname, 'public');

// One HTTP server serves the viewer page AND accepts WebSocket upgrades.
const server = http.createServer((req, res) => {
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

// WebSocket relay on the SAME port:
//   /stream - one publisher (the Android app) sends JPEG frames
//   /watch  - any number of viewers receive those frames live
const wss = new WebSocketServer({ noServer: true });
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

// Route WebSocket upgrades by path on the shared port.
server.on('upgrade', (req, socket, head) => {
  let route;
  try {
    route = new URL(req.url, `http://${req.headers.host}`).pathname;
  } catch (_) {
    socket.destroy();
    return;
  }
  if (route !== '/stream' && route !== '/watch') {
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit('connection', ws, req);
  });
});

wss.on('connection', (socket, req) => {
  const route = new URL(req.url, `http://${req.headers.host}`).pathname;

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
    socket.close(1008, 'Bad route');
  }
});

server.listen(PORT, () => {
  console.log(`ScreenShare server ready`);
  console.log(`  Viewer page -> http://localhost:${PORT}`);
  console.log(
    `  Relay      -> ws(s)://<host>:${PORT}/stream (publisher) | /watch (viewer)`
  );
});
