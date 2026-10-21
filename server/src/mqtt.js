// Alerts over MQTT (the shared broker run by ../webserver). Enabled by MQTT_URL.
//
// Every display-worthy alert on the bus (alerts/<source>/<id>, retained JSON with level, title,
// message, since and optionally expires) is relayed to the tablets through the store, under the
// tag <source>-<id>. The tablets don't speak MQTT; they keep their WebSocket to this server.
//
// - Shown: alerts at or above minLevel, and every level from allLevelSources (manual alerts).
// - Chimes: an alert chimes when it first appears, when its level rises, and every repostHours
//   while it lasts; other updates (a new number in the message) change it quietly.
// - Sending (POST /api/alerts): published as alerts/tabletpanel/<id>, so every display gets it,
//   and shown here when it comes back from the broker. durationSeconds becomes the message's
//   expiry, so the broker drops it too.
// - Dismissing on a tablet (or DELETE /api/alerts/:id) hides an alert on the tablets only: the
//   alert stays on the bus for other displays, and comes back here if its level rises or after
//   repostHours. TabletPanel's own alerts (alerts/tabletpanel/...) are cleared everywhere instead.
// - Weather alerts (src/weather-alerts.js) go out as alerts/tabletpanel/nws-<id>, but are treated
//   like a source of their own named weather: shown from minLevel (every level if allLevelSources
//   lists weather), and only hidden here when dismissed.

import { randomUUID } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { SEVERITIES, ValidationError, checkAlert } from './store.js';

const RANK = { info: 0, warning: 1, critical: 2 };
const SOURCE = 'tabletpanel';
export const WEATHER_PREFIX = 'nws-';
const SETTLE_MS = 2000; // retained alerts arrive right after subscribing; drop stale ones after that

export async function connectBus(store, opts) {
  const {
    url, username, password, minLevel = 'warning', allLevelSources = ['manual', SOURCE], repostHours = 24,
    stateFile, client: given, log = console.log, onSettings = () => {},
  } = opts;
  // The hub's per-tablet "Show alerts" setting: registry/devices/<name> entries with kind "tablet"
  // carry the app's deviceId and "alerts" ("all", "warning", "critical", "off"; absent = default).
  const tabletAlerts = new Map(); // deviceId -> setting
  const registryOwner = new Map(); // registry name -> deviceId, to forget a removed entry
  const min = RANK[minLevel] ?? 1;
  const allLevels = new Set(allLevelSources);
  const repostMs = repostHours * 3600_000;
  const readState = () => { try { return JSON.parse(fs.readFileSync(stateFile, 'utf8')); } catch { return {}; } };
  const dismissed = new Map(Object.entries(stateFile ? readState().dismissed ?? {} : {})); // tag -> { level, at }
  const saveState = () => {
    if (!stateFile) return;
    fs.mkdirSync(path.dirname(stateFile), { recursive: true });
    fs.writeFileSync(stateFile, JSON.stringify({ dismissed: Object.fromEntries(dismissed) }, null, 2));
  };
  const status = { enabled: true, connected: false, error: null };

  const client = given ?? (await import('mqtt')).default.connect(url, {
    username, password, clientId: `${SOURCE}-${os.hostname()}`, protocolVersion: 5, reconnectPeriod: 5000,
    will: { topic: `status/${SOURCE}`, payload: 'offline', retain: true, qos: 1 },
  });

  let seen = null; // tags received since (re)connecting, until the retained alerts have settled
  client.on('connect', () => {
    Object.assign(status, { connected: true, error: null });
    log('MQTT: connected');
    client.publish(`status/${SOURCE}`, 'online', { retain: true, qos: 1 });
    seen = new Set();
    client.subscribe('alerts/+/+', { qos: 1 });
    client.subscribe('registry/devices/+', { qos: 1 });
    setTimeout(() => {
      // Alerts relayed earlier that the broker no longer holds cleared while we were away.
      for (const a of store.state.alerts.filter((x) => x.bus && !seen.has(x.tag))) store.removeAlert(a.id);
      for (const tag of dismissed.keys()) if (!seen.has(tag)) dismissed.delete(tag);
      saveState();
      seen = null;
    }, SETTLE_MS);
  });
  client.on('close', () => { if (status.connected) log('MQTT: disconnected'); status.connected = false; });
  client.on('error', (e) => { status.error = e.message; });

  client.on('message', (topic, payload) => {
    if (topic.startsWith('registry/')) {
      const name = topic.split('/')[2];
      let entry = null;
      try { entry = payload.length ? JSON.parse(payload.toString()) : null; } catch { return; }
      const before = JSON.stringify([...tabletAlerts]);
      const oldId = registryOwner.get(name);
      if (oldId) { tabletAlerts.delete(oldId); registryOwner.delete(name); }
      if (entry?.kind === 'tablet' && entry.deviceId) {
        registryOwner.set(name, entry.deviceId);
        if (entry.alerts) tabletAlerts.set(entry.deviceId, entry.alerts);
      }
      if (JSON.stringify([...tabletAlerts]) !== before) onSettings();
      return;
    }
    const [, source, id] = topic.split('/');
    const tag = `${source}-${id}`;
    const clear = () => {
      dismissed.delete(tag);
      const a = store.findAlert(tag);
      if (a?.bus) store.removeAlert(a.id);
    };
    if (!payload.length) return clear();
    let a;
    try { a = JSON.parse(payload.toString()); } catch { return; }
    if (!SEVERITIES.includes(a.level) || (!a.title && !a.message)) return;
    if (a.expires && a.expires * 1000 <= Date.now()) return clear();
    seen?.add(tag);
    const from = source === SOURCE && id.startsWith(WEATHER_PREFIX) ? 'weather' : source;
    // Every alert is kept; filterFor() decides per tablet what it shows (by default: minLevel and
    // up, and every level from allLevelSources).

    const hidden = dismissed.get(tag);
    if (hidden) {
      if (RANK[a.level] <= RANK[hidden.level] && Date.now() - hidden.at < repostMs) return;
      dismissed.delete(tag);
      saveState();
    }
    const old = store.findAlert(tag);
    store.upsertBusAlert(tag, {
      title: a.title, message: a.message, severity: a.level, icon: a.icon, sound: a.sound, dismissible: a.dismissible,
      source: from, expiresAt: a.expires ? new Date(a.expires * 1000).toISOString() : null,
    }, { notify: !old || RANK[a.level] > RANK[old.severity] });
  });

  // An alert that lasts chimes again every repostHours.
  const timer = setInterval(() => {
    for (const a of store.state.alerts) {
      if (a.bus && Date.now() - Date.parse(a.createdAt) >= repostMs) store.upsertBusAlert(a.tag, a, { notify: true });
    }
  }, 60_000);
  timer.unref?.();

  const defaultWants = (a) => !a.bus || RANK[a.severity] >= min || allLevels.has(a.source);

  return {
    status: () => ({ ...status, dismissed: dismissed.size, tabletSettings: tabletAlerts.size }),

    /** Which alerts a client shows: a tablet's hub setting, else the default (also for the web UI and REST). */
    filterFor(deviceId) {
      const setting = deviceId ? tabletAlerts.get(deviceId) : undefined;
      if (setting === 'off') return () => false;
      if (setting === 'all') return () => true;
      if (setting === 'warning' || setting === 'critical') return (a) => RANK[a.severity] >= RANK[setting];
      return defaultWants;
    },

    /** POST /api/alerts: publish as alerts/tabletpanel/<id>. The tag, if given, is the id (a repeat replaces it). */
    publish(input) {
      const { title, message, severity, duration } = checkAlert(input);
      const id = input.tag === undefined ? randomUUID().slice(0, 8) : String(input.tag);
      if (!/^[\w-]{1,64}$/.test(id)) throw new ValidationError('tag must be 1-64 letters, digits, - or _');
      if (id.startsWith(WEATHER_PREFIX)) throw new ValidationError(`tags starting with ${WEATHER_PREFIX} are for weather alerts`);
      const now = Math.floor(Date.now() / 1000);
      const body = {
        level: severity, title: title || undefined, message: message || undefined, since: now,
        expires: duration > 0 ? now + duration : undefined,
        icon: typeof input.icon === 'string' ? input.icon : undefined,
        sound: typeof input.sound === 'boolean' ? input.sound : undefined,
        dismissible: typeof input.dismissible === 'boolean' ? input.dismissible : undefined,
        sentBy: typeof input.source === 'string' ? input.source : undefined,
      };
      return { id, tag: `${SOURCE}-${id}`, topic: this.publishOwn(id, body, duration), queued: !status.connected };
    },

    /** Publishes (retained) alerts/tabletpanel/<id>; the broker drops it after ttl seconds (default: at body.expires). */
    publishOwn(id, body, ttl = body.expires ? body.expires - Math.floor(Date.now() / 1000) : 0) {
      const topic = `alerts/${SOURCE}/${id}`;
      client.publish(topic, JSON.stringify(body), {
        retain: true, qos: 1, ...(ttl > 0 ? { properties: { messageExpiryInterval: ttl } } : {}),
      });
      return topic;
    },

    clearOwn(id) {
      client.publish(`alerts/${SOURCE}/${id}`, '', { retain: true, qos: 1 });
    },

    /** Dismiss / DELETE: hide on the tablets; TabletPanel's own alerts (not weather) are cleared on the bus. */
    hide(idOrTag) {
      const a = store.findAlert(idOrTag) ?? store.findAlert(`${SOURCE}-${idOrTag}`);
      if (!a) return false;
      if (a.bus && a.tag.startsWith(`${SOURCE}-`) && !a.tag.startsWith(`${SOURCE}-${WEATHER_PREFIX}`)) {
        this.clearOwn(a.tag.slice(SOURCE.length + 1));
      } else if (a.bus) {
        dismissed.set(a.tag, { level: a.severity, at: Date.now() });
        saveState();
      }
      return store.removeAlert(a.id);
    },

    hideAll() {
      for (const a of [...store.state.alerts]) this.hide(a.id);
    },

    close() {
      clearInterval(timer);
      client.publish(`status/${SOURCE}`, 'offline', { retain: true, qos: 1 });
      client.end?.();
    },
  };
}
