// US National Weather Service alerts for the panels' location, published on the MQTT bus.
// Runs when MQTT is enabled (src/mqtt.js); WEATHER_ALERTS=0 turns it off.
//
// Every intervalSeconds the server asks api.weather.gov for the alerts in force at the location:
// the one set in the web UI, else each place a tablet has reported. Each becomes a retained
// alerts/tabletpanel/nws-<id> message (this login may only publish under alerts/tabletpanel/), so
// the tablets, the hub and every other display get it, and one that ends is cleared with an empty
// message. An NWS update replaces the alert it references on the same topic, so the tablets change
// it in place and chime only if its level rises.

import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { WEATHER_PREFIX } from './mqtt.js';

const API = 'https://api.weather.gov/alerts/active';
const secs = (iso) => (iso ? Math.floor(Date.parse(iso) / 1000) : undefined);

/**
 * critical is the hub's "Severe weather" level (take shelter now, every display taken over), so
 * only an extreme threat that is happening now gets it: Tornado Warning, Extreme Wind Warning,
 * Flash Flood Emergency. Other warnings, watches and emergencies (a Hurricane Warning days out)
 * are warning; advisories and statements are info.
 */
export function levelOf(p) {
  if (p.severity === 'Extreme' && p.urgency === 'Immediate') return 'critical';
  if (p.severity === 'Extreme' || p.severity === 'Severe' || /(Warning|Watch|Emergency)$/.test(p.event ?? '')) return 'warning';
  return 'info';
}

export function startWeatherAlerts(bus, opts) {
  const {
    points, intervalSeconds = 120, userAgent = 'TabletPanel/1.0 (home weather display)', stateFile,
    fetch = globalThis.fetch, log = console.log,
  } = opts;
  const readState = () => { try { return JSON.parse(fs.readFileSync(stateFile, 'utf8')); } catch { return {}; } };
  // Topic id -> { ids: NWS ids it has carried (to follow updates), body: what was published }.
  let active = new Map(Object.entries(stateFile ? readState().active ?? {} : {}));
  const saveState = () => {
    if (!stateFile) return;
    fs.mkdirSync(path.dirname(stateFile), { recursive: true });
    fs.writeFileSync(stateFile, JSON.stringify({ active: Object.fromEntries(active) }, null, 2));
  };
  const status = { enabled: true, points: [], lastPoll: null, error: null };

  async function fetchPoint({ lat, lon }) {
    const res = await fetch(`${API}?point=${lat},${lon}&status=actual`, {
      headers: { 'User-Agent': userAgent, Accept: 'application/geo+json' },
      signal: AbortSignal.timeout(20_000),
    });
    if (!res.ok) throw new Error(`api.weather.gov: HTTP ${res.status}`);
    return (await res.json()).features ?? [];
  }

  let running = false;
  async function poll() {
    if (running) return;
    running = true;
    try {
      // ~100 m is plenty for a point lookup, and keeps tablets in the same house to one request.
      const round = (x) => Number(x.toFixed(3));
      const where = [...new Map(points().map((p) => [`${round(p.lat)},${round(p.lon)}`, { lat: round(p.lat), lon: round(p.lon) }])).values()];
      status.points = where;
      if (!where.length) {
        status.error = 'no location yet: set one in the web UI or connect a tablet';
        return;
      }
      let features;
      try {
        features = (await Promise.all(where.map(fetchPoint))).flat();
      } catch (e) {
        status.error = e.message; // keep what is published until a poll succeeds
        return;
      }
      Object.assign(status, { error: null, lastPoll: new Date().toISOString() });

      const topicOf = new Map(); // NWS id -> topic id
      for (const [tid, a] of active) for (const id of a.ids) topicOf.set(id, tid);
      const next = new Map();
      const seen = new Set(); // the same alert can come back for two points
      // Oldest first, so an update lands after (and on the topic of) the alert it references.
      for (const { properties: p } of features.sort((a, b) => Date.parse(a.properties.sent) - Date.parse(b.properties.sent))) {
        if (seen.has(p.id) || p.messageType === 'Cancel' || p.status !== 'Actual') continue;
        seen.add(p.id);
        const tid = (p.references ?? []).map((r) => topicOf.get(r.identifier)).find(Boolean)
          ?? WEATHER_PREFIX + createHash('sha1').update(p.id).digest('hex').slice(0, 12);
        topicOf.set(p.id, tid);
        const ids = [...new Set([...(next.get(tid)?.ids ?? active.get(tid)?.ids ?? []), p.id])].slice(-20);
        next.set(tid, {
          ids,
          body: {
            level: levelOf(p), title: p.event, message: p.headline || p.areaDesc,
            since: secs(p.onset ?? p.effective ?? p.sent), expires: secs(p.ends ?? p.expires),
            area: p.areaDesc, sentBy: 'nws', severity: p.severity, urgency: p.urgency, nwsId: p.id,
          },
        });
      }

      for (const [tid, a] of next) {
        if (JSON.stringify(active.get(tid)?.body) === JSON.stringify(a.body)) continue;
        if (!active.has(tid)) log(`Weather alert: ${a.body.title} (${a.body.level})`);
        bus.publishOwn(tid, a.body);
      }
      for (const [tid, a] of active) {
        if (next.has(tid)) continue;
        log(`Weather alert ended: ${a.body.title}`);
        bus.clearOwn(tid);
      }
      active = next;
      saveState();
    } finally {
      running = false;
    }
  }

  // The first poll waits a little, so tablets can reconnect and report where they are.
  const first = setTimeout(poll, 15_000);
  const timer = setInterval(poll, intervalSeconds * 1000);
  first.unref?.();
  timer.unref?.();

  return {
    poll,
    status: () => ({ ...status, active: active.size }),
    stop() {
      clearTimeout(first);
      clearInterval(timer);
    },
  };
}
