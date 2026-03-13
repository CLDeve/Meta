const http = require("http");
const fs = require("fs");
const path = require("path");
const { WebSocketServer } = require("ws");

const PORT = Number(process.env.PORT || 8181);
const HOST = process.env.HOST || "0.0.0.0";
const TURN_URL = (process.env.TURN_URL || "").trim();
const TURN_USERNAME = (process.env.TURN_USERNAME || "").trim();
const TURN_PASSWORD = (process.env.TURN_PASSWORD || "").trim();

const rooms = new Map();
let nextClientId = 1;

function getRoom(roomName) {
  if (!rooms.has(roomName)) {
    rooms.set(roomName, { broadcaster: null, viewer: null });
  }
  return rooms.get(roomName);
}

function sendJson(ws, payload) {
  if (ws && ws.readyState === ws.OPEN) {
    ws.send(JSON.stringify(payload));
  }
}

function removeClient(ws) {
  const roomName = ws.roomName;
  const role = ws.role;
  if (!roomName || !role) return;
  const room = rooms.get(roomName);
  if (!room) return;
  if (role === "broadcaster" && room.broadcaster === ws) {
    room.broadcaster = null;
    sendJson(room.viewer, { type: "peer-left" });
  }
  if (role === "viewer" && room.viewer === ws) {
    room.viewer = null;
    sendJson(room.broadcaster, { type: "peer-left" });
  }
  if (!room.broadcaster && !room.viewer) {
    rooms.delete(roomName);
  }
}

function handleJoin(ws, message) {
  const roomName = String(message.room || "cameraaccess").trim();
  const role = String(message.role || "").trim();
  if (role !== "broadcaster" && role !== "viewer") {
    sendJson(ws, { type: "error", message: "Invalid role" });
    return;
  }

  const room = getRoom(roomName);
  if (role === "broadcaster") {
    if (room.broadcaster && room.broadcaster !== ws) {
      try {
        sendJson(room.broadcaster, { type: "error", message: "Broadcaster replaced by a new connection" });
        room.broadcaster.close(1000, "replaced");
      } catch (_) {}
      removeClient(room.broadcaster);
    }
    room.broadcaster = ws;
    ws.roomName = roomName;
    ws.role = role;
    if (room.viewer) {
      sendJson(ws, { type: "viewer-ready" });
    }
    return;
  }

  if (room.viewer && room.viewer !== ws) {
    try {
      sendJson(room.viewer, { type: "error", message: "Viewer replaced by a new connection" });
      room.viewer.close(1000, "replaced");
    } catch (_) {}
    removeClient(room.viewer);
  }
  room.viewer = ws;
  ws.roomName = roomName;
  ws.role = role;
  sendJson(room.broadcaster, { type: "viewer-ready" });
}

function relay(ws, message) {
  const room = rooms.get(ws.roomName || "");
  if (!room) {
    sendJson(ws, { type: "error", message: "Join a room first" });
    return;
  }
  const target = ws.role === "broadcaster" ? room.viewer : room.broadcaster;
  if (!target) return;
  sendJson(target, message);
}

function serveFile(res, filePath, contentType) {
  const body = fs.readFileSync(filePath);
  res.writeHead(200, { "Content-Type": contentType, "Cache-Control": "no-store" });
  res.end(body);
}

const server = http.createServer((req, res) => {
  const reqUrl = new URL(req.url, `http://${req.headers.host}`);
  if (reqUrl.pathname === "/" || reqUrl.pathname === "/viewer" || reqUrl.pathname === "/viewer.html") {
    return serveFile(res, path.join(__dirname, "viewer.html"), "text/html; charset=utf-8");
  }
  if (reqUrl.pathname === "/api/turn") {
    const iceServers = [
      { urls: ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"] },
    ];
    if (TURN_URL) {
      iceServers.push({
        urls: [TURN_URL],
        username: TURN_USERNAME,
        credential: TURN_PASSWORD,
      });
    }
    res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store" });
    res.end(JSON.stringify({ iceServers }));
    return;
  }
  res.writeHead(404, { "Content-Type": "application/json" });
  res.end(JSON.stringify({ ok: false, error: "Not found" }));
});

const wss = new WebSocketServer({ server, path: "/ws" });

function heartbeat() {
  this.isAlive = true;
}

wss.on("connection", (ws) => {
  ws.clientId = `client-${nextClientId++}`;
  ws.isAlive = true;
  ws.on("pong", heartbeat);

  ws.on("message", (raw) => {
    let message;
    try {
      message = JSON.parse(String(raw));
    } catch (error) {
      sendJson(ws, { type: "error", message: "Invalid JSON" });
      return;
    }

    switch (message.type) {
      case "join":
        handleJoin(ws, message);
        break;
      case "offer":
      case "answer":
      case "ice":
        relay(ws, message);
        break;
      default:
        sendJson(ws, { type: "error", message: `Unsupported message type: ${message.type}` });
    }
  });

  ws.on("close", () => {
    removeClient(ws);
  });

  ws.on("error", () => {
    removeClient(ws);
  });
});

const interval = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) {
      try { ws.terminate(); } catch (_) {}
      removeClient(ws);
      return;
    }
    ws.isAlive = false;
    try { ws.ping(); } catch (_) {}
  });
}, 30_000);

wss.on("close", () => clearInterval(interval));

server.listen(PORT, HOST, () => {
  console.log(`Live POV signaling server listening on http://${HOST}:${PORT}`);
});
