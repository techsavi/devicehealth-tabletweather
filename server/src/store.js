// Persistent panel state: display settings, alerts and info notes.
// Everything lives in one JSON file so the server survives restarts without a database.

import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

export const FOCUS_MODES = ['auto', 'forecast', 'hourly', 'rain', 'radar', 'wind'];
export const SEVERITIES = ['info', 'warning', 'critical'];

const DEFAULT_DISPLAY = {
  focus: 'auto',
  focusUntil: null, // ISO timestamp; when it passes the display reverts to auto
  units: 'imperial', // imperial | metric
  clock24h: false,
  location: null, // { lat, lon, name } overrides the tablet's own location
  radarZoom: 7,
  // Dimming follows the tablet's light sensor when it has one (useSensor), else the start/end schedule.
  night: { enabled: false, useSensor: true, darkLux: 5, start: '22:00', end: '06:30', brightness: 0.1 },
  brightness: null, // 0.05-1, or null for the system setting
};

export class Store extends EventEmitter {
  constructor(file) {
    super();
    this.file = file;
    this.state = { display: structuredClone(DEFAULT_DISPLAY), alerts: [], notes: [] };
    this.rev = 0;
    this.load();
    // Expired alerts and remote-focus timeouts are cleaned up here so every client sees
    // the same thing, even a tablet whose clock has drifted.
    this.timer = setInterval(() => this.sweep(), 5000);
    this.timer.unref();
  }

  load() {
    try {
      const saved = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      this.state.display = { ...structuredClone(DEFAULT_DISPLAY), ...saved.display };
      this.state.display.night = { ...DEFAULT_DISPLAY.night, ...saved.display?.night };
      this.state.alerts = Array.isArray(saved.alerts) ? saved.alerts : [];
      this.state.notes = Array.isArray(saved.notes) ? saved.notes : [];
    } catch (err) {
      if (err.code !== 'ENOENT') console.warn(`Could not read ${this.file}: ${err.message}`);
    }
  }

  save() {
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(this.state, null, 2));
    fs.renameSync(tmp, this.file);
  }

  changed() {
    this.rev++;
    this.save();
    this.emit('change', this.snapshot());
  }

  snapshot() {
    return { rev: this.rev, serverTime: new Date().toISOString(), ...structuredClone(this.state) };
  }

  sweep() {
    const now = Date.now();
    let dirty = false;
    const live = this.state.alerts.filter((a) => !a.expiresAt || Date.parse(a.expiresAt) > now);
    if (live.length !== this.state.alerts.length) {
      this.state.alerts = live;
      dirty = true;
    }
    const d = this.state.display;
    if (d.focusUntil && Date.parse(d.focusUntil) <= now) {
      d.focus = 'auto';
      d.focusUntil = null;
      dirty = true;
    }
    if (dirty) this.changed();
  }

  // --- display ---

  updateDisplay(patch) {
    const d = structuredClone(this.state.display);
    const errors = [];

    if ('focus' in patch) {
      if (!FOCUS_MODES.includes(patch.focus)) errors.push(`focus must be one of ${FOCUS_MODES.join(', ')}`);
      else d.focus = patch.focus;
      // Changing focus without a duration clears any earlier timeout.
      if (!('focusMinutes' in patch) && !('focusUntil' in patch)) d.focusUntil = null;
    }
    if ('focusMinutes' in patch) {
      const m = patch.focusMinutes;
      if (m === null || m === 0) d.focusUntil = null;
      else if (typeof m !== 'number' || m < 0) errors.push('focusMinutes must be a positive number');
      else d.focusUntil = new Date(Date.now() + m * 60000).toISOString();
    } else if ('focusUntil' in patch) {
      if (patch.focusUntil !== null && Number.isNaN(Date.parse(patch.focusUntil))) errors.push('focusUntil must be an ISO date');
      else d.focusUntil = patch.focusUntil;
    }
    if ('units' in patch) {
      if (!['imperial', 'metric'].includes(patch.units)) errors.push('units must be imperial or metric');
      else d.units = patch.units;
    }
    if ('clock24h' in patch) d.clock24h = Boolean(patch.clock24h);
    if ('location' in patch) {
      const l = patch.location;
      if (l === null) d.location = null;
      else if (typeof l?.lat !== 'number' || typeof l?.lon !== 'number' || Math.abs(l.lat) > 90 || Math.abs(l.lon) > 180) {
        errors.push('location must be null or { lat, lon, name? }');
      } else d.location = { lat: l.lat, lon: l.lon, name: l.name ? String(l.name) : null };
    }
    if ('radarZoom' in patch) {
      const z = Number(patch.radarZoom);
      if (!(z >= 3 && z <= 12)) errors.push('radarZoom must be 3-12');
      else d.radarZoom = Math.round(z);
    }
    if ('brightness' in patch) {
      const b = patch.brightness;
      if (b === null) d.brightness = null;
      else if (typeof b !== 'number' || b < 0.01 || b > 1) errors.push('brightness must be null or 0.01-1');
      else d.brightness = b;
    }
    if ('night' in patch) {
      const n = { ...d.night, ...patch.night };
      const hhmm = /^([01]\d|2[0-3]):[0-5]\d$/;
      if (!hhmm.test(n.start) || !hhmm.test(n.end)) errors.push('night.start/end must be HH:MM');
      else if (typeof n.brightness !== 'number' || n.brightness < 0.01 || n.brightness > 1) errors.push('night.brightness must be 0.01-1');
      else if (typeof n.darkLux !== 'number' || n.darkLux < 0 || n.darkLux > 1000) errors.push('night.darkLux must be 0-1000');
      else {
        d.night = {
          enabled: Boolean(n.enabled),
          useSensor: Boolean(n.useSensor),
          darkLux: n.darkLux,
          start: n.start,
          end: n.end,
          brightness: n.brightness,
        };
      }
    }

    if (errors.length) throw new ValidationError(errors.join('; '));
    this.state.display = d;
    this.changed();
    return d;
  }

  // --- alerts ---

  addAlert(input) {
    const { title, message, severity, duration } = checkAlert(input);
    const now = new Date();
    const alert = {
      id: randomUUID(),
      title: title || null,
      message: message || null,
      severity,
      icon: typeof input.icon === 'string' ? input.icon : null,
      sound: input.sound ?? severity !== 'info',
      dismissible: input.dismissible ?? true,
      source: typeof input.source === 'string' ? input.source : null,
      createdAt: now.toISOString(),
      expiresAt: duration > 0 ? new Date(now.getTime() + duration * 1000).toISOString() : null,
    };
    // A repeated tag replaces the earlier alert instead of stacking (e.g. "doorbell").
    if (typeof input.tag === 'string' && input.tag) {
      alert.tag = input.tag;
      this.state.alerts = this.state.alerts.filter((a) => a.tag !== input.tag);
    }
    this.state.alerts.push(alert);
    this.changed();
    return alert;
  }

  findAlert(idOrTag) {
    return this.state.alerts.find((a) => a.id === idOrTag || a.tag === idOrTag) ?? null;
  }

  /**
   * An alert relayed from the MQTT bus (src/mqtt.js), kept under its tag (<source>-<id>).
   * notify: a new id and time, which makes the tablets chime and show it again; otherwise it is
   * updated in place (a new number in the message) without disturbing anyone.
   */
  upsertBusAlert(tag, input, { notify }) {
    const old = this.state.alerts.find((a) => a.tag === tag);
    const fresh = notify || !old;
    const alert = {
      id: fresh ? randomUUID() : old.id,
      title: input.title || null,
      message: input.message || null,
      severity: input.severity,
      icon: typeof input.icon === 'string' ? input.icon : null,
      sound: input.sound ?? input.severity !== 'info',
      dismissible: input.dismissible ?? true,
      source: input.source ?? null,
      createdAt: fresh ? new Date().toISOString() : old.createdAt,
      expiresAt: input.expiresAt ?? null,
      tag,
      bus: true,
    };
    if (old && JSON.stringify(old) === JSON.stringify(alert)) return old;
    this.state.alerts = this.state.alerts.filter((a) => a.tag !== tag);
    this.state.alerts.push(alert);
    this.changed();
    return alert;
  }

  removeAlert(id) {
    const before = this.state.alerts.length;
    this.state.alerts = this.state.alerts.filter((a) => a.id !== id && a.tag !== id);
    if (this.state.alerts.length === before) return false;
    this.changed();
    return true;
  }

  clearAlerts() {
    this.state.alerts = [];
    this.changed();
  }

  // --- notes (persistent info items shown in the footer ticker) ---

  addNote(input) {
    const text = typeof input.text === 'string' ? input.text.trim() : '';
    if (!text) throw new ValidationError('text is required');
    const note = {
      id: randomUUID(),
      text,
      icon: typeof input.icon === 'string' ? input.icon : null,
      createdAt: new Date().toISOString(),
    };
    this.state.notes.push(note);
    this.changed();
    return note;
  }

  removeNote(id) {
    const before = this.state.notes.length;
    this.state.notes = this.state.notes.filter((n) => n.id !== id);
    if (this.state.notes.length === before) return false;
    this.changed();
    return true;
  }
}

export class ValidationError extends Error {}

/** Validates a new alert (POST /api/alerts): its text, severity and lifetime in seconds (0 = until dismissed). */
export function checkAlert(input) {
  const message = typeof input.message === 'string' ? input.message.trim() : '';
  const title = typeof input.title === 'string' ? input.title.trim() : '';
  if (!message && !title) throw new ValidationError('message or title is required');
  const severity = input.severity ?? 'info';
  if (!SEVERITIES.includes(severity)) throw new ValidationError(`severity must be one of ${SEVERITIES.join(', ')}`);
  // Default lifetimes: info 5 min, warning 30 min, critical until dismissed. 0 = until dismissed.
  const defaults = { info: 300, warning: 1800, critical: 0 };
  const duration = input.durationSeconds ?? defaults[severity];
  if (typeof duration !== 'number' || duration < 0) throw new ValidationError('durationSeconds must be >= 0');
  return { title, message, severity, duration };
}
