// TabletPanel control server: web UI, REST API and the WebSocket the tablets stay connected to.

import express from 'express';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';
import { Bonjour } from 'bonjour-service';
import { Store, ValidationError, FOCUS_MODES } from './store.js';
import { Devices, DEVICE_COMMANDS } from './devices.js';
import { connectBus } from './mqtt.js';
import { startWeatherAlerts } from './weather-alerts.js';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const PORT = Number(process.env.PORT ?? 8787);
const API_KEY = process.env.API_KEY || null;
const DATA_FILE = process.env.DATA_FILE ?? path.join(root, 'data', 'state.json');
const env = process.env;
const config = readConfig(env.CONFIG_FILE ?? path.join(root, 'config.json'));

/** Site-specific settings kept out of git (config.json; see config.example.json). */
function readConfig(file) {
  let c;
  try {
    c = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (err) {
    if (err.code !== 'ENOENT') console.warn(`Could not read ${file}: ${err.message}`);
    return {};
  }
  const l = c.location;
  if (l && !(typeof l.lat === 'number' && typeof l.lon === 'number' && Math.abs(l.lat) <= 90 && Math.abs(l.lon) <= 180)) {
    console.warn(`${file}: location must be { "lat": number, "lon": number, "name"?: string }; ignored`);
    delete c.location;
  }
  return c;
}

const store = new Store(DATA_FILE);
const devices = new Devices();
// With MQTT_URL, alerts come from (and are sent through) the shared MQTT broker: src/mqtt.js.
const bus = env.MQTT_URL
  ? await connectBus(store, {
      url: env.MQTT_URL, username: env.MQTT_USERNAME, password: env.MQTT_PASSWORD,
      minLevel: env.ALERT_MIN_LEVEL || 'warning',
      allLevelSources: (env.ALERT_ALL_LEVEL_SOURCES ?? 'manual,tabletpanel').split(',').filter(Boolean),
      repostHours: Number(env.ALERT_REPOST_HOURS ?? 24),
      stateFile: path.join(path.dirname(DATA_FILE), 'mqtt-state.json'),
      onSettings: () => sendStateToAll(),   // a tablet's "Show alerts" setting changed on the hub
    })
  : null;
// NWS watches and warnings for the panels' location, published on the bus: src/weather-alerts.js.
const weather = bus && env.WEATHER_ALERTS !== '0'
  ? startWeatherAlerts(bus, {
      points: alertPoints,
      intervalSeconds: Number(env.WEATHER_ALERTS_INTERVAL ?? 120),
      userAgent: env.NWS_USER_AGENT || config.nwsUserAgent || undefined,
      stateFile: path.join(path.dirname(DATA_FILE), 'weather-state.json'),
    })
  : null;

/** The location set in the web UI, else config.json's, else wherever the tablets say they are. */
function alertPoints() {
  const fixed = store.state.display.location ?? config.location;
  if (fixed) return [fixed];
  return devices.list().map((d) => d.status).filter((s) => typeof s?.lat === 'number' && typeof s?.lon === 'number');
}
const app = express();
app.use(express.json({ limit: '64kb' }));

// --- auth: optional shared key for everything under /api and /ws ---

function keyFrom(req) {
  const auth = req.headers.authorization ?? '';
  if (auth.startsWith('Bearer ')) return auth.slice(7);
  return req.headers['x-api-key'] ?? new URL(req.url, 'http://x').searchParams.get('key');
}

app.use('/api', (req, res, next) => {
  if (!API_KEY || req.path === '/health' || keyFrom(req) === API_KEY) return next();
  res.status(401).json({ error: 'missing or invalid API key' });
});

// --- REST API ---

const api = express.Router();
app.use('/api', api);

api.get('/health', (req, res) => res.json({
  ok: true, rev: store.rev, devices: devices.list().length, auth: Boolean(API_KEY), mqtt: bus ? bus.status() : { enabled: false },
  weatherAlerts: weather ? weather.status() : { enabled: false },
}));

api.get('/state', (req, res) => res.json({ ...stateFor(null), devices: devices.list() }));

api.get('/display', (req, res) => res.json(store.state.display));
api.patch('/display', (req, res) => res.json(store.updateDisplay(req.body ?? {})));

// Shorthand: POST /api/focus { "focus": "radar", "minutes": 15 }
api.post('/focus', (req, res) => {
  const { focus, minutes } = req.body ?? {};
  res.json(store.updateDisplay(minutes ? { focus, focusMinutes: minutes } : { focus }));
});
api.get('/focus/modes', (req, res) => res.json(FOCUS_MODES));

api.get('/alerts', (req, res) => res.json(stateFor(null).alerts));
// With MQTT the alert is published to the bus (202) and shown when it comes back from the broker.
api.post('/alerts', (req, res) => (bus
  ? res.status(202).json(bus.publish(req.body ?? {}))
  : res.status(201).json(store.addAlert(req.body ?? {}))));
api.delete('/alerts', (req, res) => {
  if (bus) bus.hideAll();
  else store.clearAlerts();
  res.status(204).end();
});
api.delete('/alerts/:id', (req, res) => {
  if (!(bus ? bus.hide(req.params.id) : store.removeAlert(req.params.id))) return res.status(404).json({ error: 'no such alert' });
  res.status(204).end();
});

api.get('/notes', (req, res) => res.json(store.state.notes));
api.post('/notes', (req, res) => res.status(201).json(store.addNote(req.body ?? {})));
api.delete('/notes/:id', (req, res) => {
  if (!store.removeNote(req.params.id)) return res.status(404).json({ error: 'no such note' });
  res.status(204).end();
});

api.get('/devices', (req, res) => res.json(devices.list()));
api.post('/devices/:id/command', (req, res) => {
  const command = req.body?.command;
  if (!DEVICE_COMMANDS.includes(command)) {
    return res.status(400).json({ error: `command must be one of ${DEVICE_COMMANDS.join(', ')}` });
  }
  const sent = req.params.id === 'all' ? devices.broadcastCommand(command) : devices.sendCommand(req.params.id, command);
  if (!sent) return res.status(404).json({ error: 'device not connected' });
  res.json({ sent });
});

api.use((req, res) => res.status(404).json({ error: 'not found' }));

app.use(express.static(path.join(root, 'public')));

app.use((err, req, res, next) => {
  if (err instanceof ValidationError) return res.status(400).json({ error: err.message });
  if (err.type === 'entity.parse.failed') return res.status(400).json({ error: 'invalid JSON body' });
  console.error(err);
  res.status(500).json({ error: 'internal error' });
});

// --- WebSocket: tablets connect here; the web UI also listens for live updates ---

const server = http.createServer(app);
const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname !== '/ws' || (API_KEY && keyFrom(req) !== API_KEY)) {
    console.log(`ws rejected ${peer(req)} ${url.pathname}`);
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    return socket.destroy();
  }
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req, url.searchParams.get('role') ?? 'display'));
});

wss.on('connection', (ws, req, role) => {
  ws.role = role;
  ws.peer = peer(req);
  console.log(`ws open ${ws.peer} (${role})`);
  ws.isAlive = true;
  ws.on('pong', () => (ws.isAlive = true));
  send(ws, { type: 'state', state: stateFor(ws) });
  if (role === 'ui') send(ws, { type: 'devices', devices: devices.list() });

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw);
    } catch {
      return;
    }
    if (role !== 'display') return;
    if (msg.type === 'hello') {
      devices.hello(ws, msg, req.socket.remoteAddress);
      send(ws, { type: 'state', state: stateFor(ws) });   // now that we know which tablet it is
    }
    else if (msg.type === 'status') devices.status(ws, msg);
    else if (msg.type === 'dismiss' && msg.alertId) (bus ? bus.hide(msg.alertId) : store.removeAlert(msg.alertId));
  });
  ws.on('close', (code) => {
    console.log(`ws closed ${ws.peer} (${ws.deviceId ?? ws.role}) code ${code}`);
    devices.disconnect(ws);
  });
});

/** Remote address of a request, without the IPv4-mapped IPv6 prefix. */
function peer(req) {
  return req.socket.remoteAddress?.replace(/^::ffff:/, '') ?? '?';
}

function send(ws, msg) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(msg));
}

function broadcast(msg, role) {
  const data = JSON.stringify(msg);
  for (const ws of wss.clients) if (ws.readyState === ws.OPEN && (!role || ws.role === role)) ws.send(data);
}

/**
 * The state a client is sent. With the MQTT bus, a tablet gets only the alerts its "Show alerts"
 * setting on the hub allows (registry/devices/<name>); the web UI and REST get the default view.
 */
function stateFor(ws, state = store.snapshot()) {
  if (!bus) return state;
  const wants = bus.filterFor(ws?.role === 'display' ? ws.deviceId : null);
  return { ...state, alerts: state.alerts.filter(wants) };
}

function sendStateToAll(state = store.snapshot()) {
  for (const ws of wss.clients) send(ws, { type: 'state', state: stateFor(ws, state) });
}

store.on('change', (state) => sendStateToAll(state));
devices.on('change', (list) => broadcast({ type: 'devices', devices: list }, 'ui'));

// Drop connections that stop answering pings (tablet lost Wi-Fi, went to sleep, ...).
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      console.log(`ws ${ws.peer} (${ws.deviceId ?? ws.role}) stopped answering pings`);
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, 20000).unref();

// --- start ---

server.listen(PORT, () => {
  const ipv4 = Object.values(os.networkInterfaces())
    .flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal)
    .map((i) => i.address);
  console.log(`TabletPanel server listening on port ${PORT}${API_KEY ? ' (API key required)' : ''}`);
  for (const a of ipv4) console.log(`  http://${a}:${PORT}`);

  // Advertise on the LAN so tablets can find the server without typing an address.
  if (process.env.MDNS !== '0') advertise(process.env.MDNS_INTERFACE ? [process.env.MDNS_INTERFACE] : ipv4);
});

/**
 * One mDNS responder per network adapter. With a single responder, Windows sends from whichever
 * adapter it considers default (often a WSL/VirtualBox/VPN one, so Wi-Fi never hears it), and the
 * record lists every address on the machine, so a tablet may pick one it can't reach.
 */
function advertise(addresses) {
  const responders = [];
  for (const address of addresses) {
    try {
      const bonjour = new Bonjour({ interface: address, bind: '0.0.0.0' });
      const service = bonjour.publish({ name: `TabletPanel on ${os.hostname()}`, type: 'tabletpanel', port: PORT, txt: { path: '/ws' } });
      // Only announce the address that is reachable on this adapter's network.
      const records = service.records.bind(service);
      service.records = () => records().filter((r) => (r.type !== 'A' && r.type !== 'AAAA') || r.data === address);
      responders.push(bonjour);
    } catch (err) {
      console.warn(`mDNS advertising on ${address} failed: ${err.message}`);
    }
  }
  const stop = () => {
    weather?.stop();
    bus?.close();
    let pending = responders.length;
    if (!pending) process.exit(0);
    for (const b of responders) b.unpublishAll(() => --pending || process.exit(0));
  };
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
}
