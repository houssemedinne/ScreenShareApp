'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

// Railway / PaaS exposes a single public port via $PORT. HTTP and WebSocket
// SHARE that one port, so the relay is reachable on the same URL as the viewer
// page.
const PORT = Number(process.env.PORT || 3000);
const PUBLIC_DIR = path.join(__dirname, 'public');

const wss = new WebSocketServer({ noServer: true });

// Multi-stream registry: streamId -> { id, device, width, height, latestFrame, watchers, updatedAt }
const streams = new Map();
let idCounter = 0;

function isOpen(socket) {
  return socket.readyState === socket.OPEN;
}

function streamInfo(s) {
  return {
    id: s.id,
    device: s.device,
    width: s.width,
    height: s.height,
    viewers: s.watchers ? s.watchers.size : 0,
  };
}

function latestStream() {
  let best = null;
  for (const s of streams.values()) {
    if (!best || s.updatedAt > best.updatedAt) best = s;
  }
  return best;
}

function httpJson(res, status, obj) {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(obj));
}

// ---------------------------------------------------------------------------
// HTTP server: viewer page + JSON API
// ---------------------------------------------------------------------------
const server = http.createServer((req, res) => {
  let pathname;
  try {
    pathname = new URL(req.url, 'http://x').pathname;
  } catch (_) {
    res.writeHead(400).end();
    return;
  }

  if (pathname === '/api/streams') {
    const list = [...streams.values()]
      .map(streamInfo)
      .sort((a, b) => b.updatedAt - a.updatedAt);
    return httpJson(res, 200, { streams: list });
  }
  if (pathname === '/api/health') {
    return httpJson(res, 200, { ok: true, streams: streams.size });
  }

  // static viewer page
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
    const type = path.extname(full) === '.html' ? 'text/html' : 'application/octet-stream';
    res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store' });
    res.end(data);
  });
});

// Route WebSocket upgrades by path on the shared port.
server.on('upgrade', (req, socket, head) => {
  let url;
  try {
    url = new URL(req.url, 'http://x');
  } catch (_) {
    socket.destroy();
    return;
  }
  if (url.pathname !== '/stream' && url.pathname !== '/watch') {
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit('connection', ws, req, url);
  });
});

wss.on('connection', (socket, req, url) => {
  if (url.pathname === '/stream') {
    // PUBLISHER: each connection is its own stream.
    const stream = {
      id: 's' + ++idCounter,
      device: 'Unknown',
      width: 0,
      height: 0,
      latestFrame: null,
      watchers: new Set(),
      updatedAt: Date.now(),
    };
    streams.set(stream.id, stream);
    console.log(`[publisher] connected (${streams.size} active)`);

    socket.on('message', (data, isBinary) => {
      if (isBinary) {
        stream.latestFrame = data;
        stream.updatedAt = Date.now();
        for (const w of stream.watchers) {
          if (isOpen(w)) {
            try {
              w.send(data);
            } catch (_) {
              /* keep going */
            }
          }
        }
      } else {
        const text = String(data);
        try {
          const msg = JSON.parse(text);
          if (msg.type === 'stream') {
            stream.device = msg.device || stream.device;
            stream.width = msg.width || stream.width;
            stream.height = msg.height || stream.height;
            console.log(
              `[publisher] stream ${stream.id} started: ${stream.device} ${stream.width}x${stream.height}`
            );
            try {
              socket.send(JSON.stringify({ type: 'stream-ready', id: stream.id }));
            } catch (_) {}
          } else if (msg.type === 'stop') {
            console.log(`[publisher] stream ${stream.id} stopped`);
          }
        } catch (_) {
          /* ignore malformed text */
        }
      }
    });

    const closeStream = () => {
      if (!streams.has(stream.id)) return;
      streams.delete(stream.id);
      for (const w of stream.watchers) {
        if (isOpen(w)) {
          try {
            w.close(1000, 'stream ended');
          } catch (_) {}
        }
      }
      stream.watchers.clear();
      console.log(`[publisher] stream ${stream.id} disconnected (${streams.size} active)`);
    };
    socket.on('close', closeStream);
    socket.on('error', closeStream);
  } else if (url.pathname === '/watch') {
    // VIEWER: subscribe to a specific stream, or the latest if none given.
    const requested = url.searchParams.get('stream');
    const stream = requested ? streams.get(requested) : latestStream();
    if (!stream) {
      try {
        socket.send(JSON.stringify({ type: 'no-stream' }));
        socket.close(1008, 'no stream');
      } catch (_) {}
      return;
    }
    stream.watchers.add(socket);
    console.log(`[viewer] connected to ${stream.id} (${stream.watchers.size} watchers)`);

    try {
      socket.send(
        JSON.stringify({
          type: 'meta',
          id: stream.id,
          device: stream.device,
          width: stream.width,
          height: stream.height,
        })
      );
    } catch (_) {}
    if (stream.latestFrame && isOpen(socket)) {
      try {
        socket.send(stream.latestFrame);
      } catch (_) {}
    }

    const detach = () => {
      stream.watchers.delete(socket);
    };
    socket.on('close', detach);
    socket.on('error', detach);
  } else {
    socket.close(1008, 'Bad route');
  }
});

server.listen(PORT, () => {
  console.log(`ScreenShare server ready`);
  console.log(`  Viewer    -> http://localhost:${PORT}`);
  console.log(`  API       -> /api/streams , /api/health`);
  console.log(`  Publisher -> ws(s)://<host>:${PORT}/stream (one per device)`);
  console.log(`  Viewer    -> ws(s)://<host>:${PORT}/watch?stream=<id>`);
});
