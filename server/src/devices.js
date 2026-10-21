// Tracks the tablets connected over WebSocket and what they last reported.

import { EventEmitter } from 'node:events';

export const DEVICE_COMMANDS = ['refresh-weather', 'identify', 'reload'];

export class Devices extends EventEmitter {
  constructor() {
    super();
    this.byId = new Map(); // deviceId -> { info, ws }
  }

  hello(ws, msg, address) {
    const id = String(msg.deviceId ?? '').slice(0, 64);
    if (!id) return;
    const prev = this.byId.get(id);
    // A tablet that reconnects before its old socket times out replaces it.
    if (prev?.ws && prev.ws !== ws) prev.ws.terminate();
    ws.deviceId = id;
    this.byId.set(id, {
      ws,
      info: {
        ...prev?.info,
        id,
        name: String(msg.name ?? id).slice(0, 80),
        appVersion: msg.appVersion ?? null,
        model: msg.model ?? null,
        address: address?.replace(/^::ffff:/, '') ?? null,
        connected: true,
        connectedAt: new Date().toISOString(),
        lastSeen: new Date().toISOString(),
      },
    });
    this.emit('change', this.list());
  }

  status(ws, msg) {
    const entry = ws.deviceId && this.byId.get(ws.deviceId);
    if (!entry) return;
    const { type, ...status } = msg;
    entry.info.status = status;
    entry.info.lastSeen = new Date().toISOString();
    this.emit('change', this.list());
  }

  disconnect(ws) {
    const entry = ws.deviceId && this.byId.get(ws.deviceId);
    if (!entry || entry.ws !== ws) return;
    entry.ws = null;
    entry.info.connected = false;
    entry.info.lastSeen = new Date().toISOString();
    this.emit('change', this.list());
  }

  list() {
    return [...this.byId.values()].map((e) => e.info);
  }

  sendCommand(id, command) {
    const ws = this.byId.get(id)?.ws;
    if (!ws || ws.readyState !== ws.OPEN) return 0;
    ws.send(JSON.stringify({ type: 'command', command }));
    return 1;
  }

  broadcastCommand(command) {
    let n = 0;
    for (const id of this.byId.keys()) n += this.sendCommand(id, command);
    return n;
  }
}
