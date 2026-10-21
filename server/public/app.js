// TabletPanel control UI. Talks to the REST API and listens on /ws for live state.

const MODES = [
  { id: 'auto', ico: '✨', lbl: 'Auto', desc: 'Tablet decides by the weather' },
  { id: 'forecast', ico: '📅', lbl: 'Forecast', desc: '10-day outlook' },
  { id: 'hourly', ico: '🕒', lbl: 'Hourly', desc: 'Next 24 hours' },
  { id: 'rain', ico: '🌧️', lbl: 'Rain', desc: 'Rain timing & totals' },
  { id: 'radar', ico: '📡', lbl: 'Radar', desc: 'Animated radar map' },
  { id: 'wind', ico: '💨', lbl: 'Wind', desc: 'Wind & gusts' },
];

const PRESETS = [
  { label: 'Dinner is ready', body: { title: 'Dinner is ready', severity: 'info', durationSeconds: 300 } },
  { label: 'Doorbell', body: { title: 'Someone is at the door', severity: 'warning', durationSeconds: 60, tag: 'doorbell' } },
  { label: 'Leaving soon', body: { title: 'Leaving in 10 minutes', severity: 'info', durationSeconds: 600 } },
  { label: 'Severe weather', body: { title: 'Severe weather', message: 'Take shelter now', severity: 'critical' } },
];

// Everything is resolved against the page's own URL, so the UI also works behind a path-prefix
// proxy (e.g. http://host/tabletpanel/) as well as at http://host:8787/.
const BASE = new URL('.', location.href);

let state = null;
let devices = [];
let busEnabled = false; // alerts come from the MQTT bus (GET /api/health mqtt.enabled)
let apiKey = localStorage.getItem('tabletpanel.key') ?? '';
const $ = (sel) => document.querySelector(sel);

// --- API helpers ---

async function api(method, path, body) {
  const res = await fetch(new URL(`api${path}`, BASE), {
    method,
    headers: { 'Content-Type': 'application/json', ...(apiKey ? { 'X-API-Key': apiKey } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 401) {
    const k = prompt('This server requires an API key:');
    if (k) {
      apiKey = k.trim();
      localStorage.setItem('tabletpanel.key', apiKey);
      return api(method, path, body);
    }
  }
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.error ?? `${res.status} ${res.statusText}`);
  }
  return res.status === 204 ? null : res.json();
}

function toast(text) {
  const t = $('#toast');
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (t.hidden = true), 2500);
}

async function run(fn, okMsg) {
  try {
    await fn();
    if (okMsg) toast(okMsg);
  } catch (e) {
    toast(`Error: ${e.message}`);
  }
}

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const time = (iso) => new Date(iso).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });

function ago(iso) {
  const s = Math.round((Date.now() - Date.parse(iso)) / 1000);
  if (s < 60) return 'just now';
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  return `${Math.round(s / 3600)} h ago`;
}

function remaining(iso) {
  if (!iso) return 'until dismissed';
  const s = Math.max(0, Math.round((Date.parse(iso) - Date.now()) / 1000));
  return s < 90 ? `${s}s left` : `${Math.round(s / 60)} min left`;
}

// --- live connection ---

function connect() {
  const url = new URL('ws', BASE);
  url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  url.searchParams.set('role', 'ui');
  if (apiKey) url.searchParams.set('key', apiKey);
  const ws = new WebSocket(url);
  ws.onopen = () => setConn(true);
  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.type === 'state') {
      const first = !state;
      state = msg.state;
      render(first);
    } else if (msg.type === 'devices') {
      devices = msg.devices;
      renderDevices();
    }
  };
  ws.onclose = () => {
    setConn(false);
    setTimeout(connect, 3000);
  };
}

function setConn(ok) {
  const el = $('#conn');
  el.className = `pill ${ok ? 'online' : 'offline'}`;
  el.textContent = ok ? 'live' : 'disconnected';
}

// --- rendering ---

function render(first) {
  renderFocus();
  renderAlerts();
  renderNotes();
  // Don't clobber the settings form while someone is editing it.
  if (first || !$('#settingsForm').contains(document.activeElement)) fillSettings();
}

function renderFocus() {
  const d = state.display;
  $('#focusModes').innerHTML = MODES.map(
    (m) => `<button class="mode ${d.focus === m.id ? 'active' : ''}" data-mode="${m.id}">
      <span class="ico">${m.ico}</span><span class="lbl">${m.lbl}</span><span class="desc">${m.desc}</span></button>`,
  ).join('');
  const shown = devices.find((x) => x.connected && x.status?.focus)?.status;
  let status = d.focus === 'auto' ? 'Auto' : `Remote: ${MODES.find((m) => m.id === d.focus)?.lbl}`;
  if (d.focusUntil) status += ` until ${time(d.focusUntil)}`;
  if (d.focus === 'auto' && shown) status += ` · tablet is showing ${shown.focus}`;
  $('#focusStatus').textContent = status;
}

function renderAlerts() {
  const list = state.alerts;
  $('#alerts').innerHTML = list.length
    ? list
        .map(
          (a) => `<li class="${a.severity}"><div class="body">
            <strong>${esc(a.title ?? a.message)}</strong>${a.title && a.message ? `<div>${esc(a.message)}</div>` : ''}
            <div class="meta">${a.severity} · sent ${time(a.createdAt)}${a.source ? ` by ${esc(a.source)}` : ''} · ${remaining(a.expiresAt)}</div>
          </div><button class="ghost small danger" data-del-alert="${a.id}"${busEnabled && a.bus && a.source !== 'tabletpanel' ? ' title="Hides it on the tablets; it stays on the bus for other displays"' : ''}>${busEnabled && a.bus && a.source !== 'tabletpanel' ? 'Hide on tablets' : 'Remove'}</button></li>`,
        )
        .join('')
    : '<li class="empty">Nothing showing.</li>';
}

function renderNotes() {
  $('#notes').innerHTML = state.notes.length
    ? state.notes
        .map((n) => `<li><div class="body">${esc(n.text)}</div><button class="ghost small danger" data-del-note="${n.id}">Remove</button></li>`)
        .join('')
    : '<li class="empty">No notes.</li>';
}

function renderDevices() {
  const el = $('#devices');
  if (!devices.length) {
    el.innerHTML = '<p class="muted">No tablets have connected yet. Open the TabletPanel app on the tablet; it finds this server automatically.</p>';
    return;
  }
  el.innerHTML = devices
    .map((d) => {
      const s = d.status ?? {};
      const wx = s.temperature != null ? `${Math.round(s.temperature)}° ${esc(s.condition ?? '')}` : '—';
      const where = s.place ?? (s.lat != null ? `${s.lat.toFixed(3)}, ${s.lon.toFixed(3)}` : 'location unknown');
      return `<div class="device">
        <div class="name">${esc(d.name)} <span class="pill ${d.connected ? 'online' : 'offline'}">${d.connected ? 'online' : `offline · ${ago(d.lastSeen)}`}</span></div>
        <div class="wx">${wx}</div>
        <div class="meta">📍 ${esc(where)}${s.locationSource ? ` (${esc(s.locationSource)})` : ''}</div>
        <div class="meta">Showing <b>${esc(s.focus ?? '?')}</b>${s.outlook ? ` · ${esc(s.outlook)}` : ''}</div>
        <div class="meta">${s.lightSensor == null ? '' : s.lightSensor ? `💡 ${s.lux != null ? `${s.lux} lux` : 'light sensor'}` : 'No light sensor'}${s.dimmed ? ` · 🌙 dimmed (${esc(s.dimmed)})` : ''}</div>
        <div class="meta">${esc(d.address ?? '')}${s.battery != null ? ` · 🔋 ${s.battery}%${s.charging ? ' ⚡' : ''}` : ''}${s.weatherUpdated ? ` · weather ${ago(s.weatherUpdated)}` : ''}${d.appVersion ? ` · v${esc(d.appVersion)}` : ''}</div>
        ${d.connected ? `<div class="actions">
          <button class="small" data-cmd="identify" data-dev="${esc(d.id)}">Identify</button>
          <button class="small" data-cmd="refresh-weather" data-dev="${esc(d.id)}">Refresh weather</button>
          <button class="small" data-cmd="reload" data-dev="${esc(d.id)}">Restart app</button></div>` : ''}
      </div>`;
    })
    .join('');
  if (state) renderFocus();
}

function fillSettings() {
  const d = state.display;
  const f = $('#settingsForm').elements;
  f.units.value = d.units;
  f.clock24h.checked = d.clock24h;
  f.useTablet.checked = !d.location;
  f.lat.value = d.location?.lat ?? '';
  f.lon.value = d.location?.lon ?? '';
  f.locName.value = d.location?.name ?? '';
  f.brightness.value = d.brightness == null ? 0 : Math.round(d.brightness * 100);
  f.nightEnabled.checked = d.night.enabled;
  f.nightUseSensor.checked = d.night.useSensor;
  f.nightDarkLux.value = d.night.darkLux;
  f.nightStart.value = d.night.start;
  f.nightEnd.value = d.night.end;
  f.nightBrightness.value = Math.round(d.night.brightness * 100);
  f.radarZoom.value = d.radarZoom;
  syncSettingsUi();
}

function syncSettingsUi() {
  const f = $('#settingsForm').elements;
  $('#locFields').style.display = f.useTablet.checked ? 'none' : '';
  $('#geoBtn').style.display = f.useTablet.checked ? 'none' : '';
  $('#nightFields').style.display = f.nightEnabled.checked ? '' : 'none';
  $('.sensorOnly').style.display = f.nightUseSensor.checked ? '' : 'none';
  $('#scheduleLabel').textContent = f.nightUseSensor.checked ? 'Schedule for tablets without a sensor:' : 'Schedule:';
  $('#brightnessOut').textContent = Number(f.brightness.value) === 0 ? 'system' : `${f.brightness.value}%`;
}

function renderApiExamples() {
  const base = BASE.href.replace(/\/$/, '');
  const key = apiKey ? ` -H "X-API-Key: ${apiKey}"` : '';
  $('#apiExamples').textContent = [
    '# Show an alert (severity: info | warning | critical; durationSeconds 0 = until dismissed)',
    `curl -X POST ${base}/api/alerts${key} -H "Content-Type: application/json" \\`,
    `  -d '{"title":"Garage door open","message":"Open for 15 minutes","severity":"warning","durationSeconds":600}'`,
    '',
    '# Switch the display to radar for 15 minutes',
    `curl -X POST ${base}/api/focus${key} -H "Content-Type: application/json" -d '{"focus":"radar","minutes":15}'`,
    '',
    '# Remove an alert by id or tag, or all alerts',
    `curl -X DELETE ${base}/api/alerts/doorbell${key}`,
    `curl -X DELETE ${base}/api/alerts${key}`,
    '',
    '# Everything else',
    `curl ${base}/api/state${key}`,
    `curl -X PATCH ${base}/api/display${key} -H "Content-Type: application/json" -d '{"units":"metric"}'`,
  ].join('\n');
}

// --- events ---

document.addEventListener('click', (e) => {
  const t = e.target.closest('button');
  if (!t) return;
  if (t.dataset.mode) {
    const minutes = t.dataset.mode === 'auto' ? 0 : Number($('#focusMinutes').value);
    run(() => api('PATCH', '/display', { focus: t.dataset.mode, focusMinutes: minutes || null }));
  } else if (t.dataset.delAlert) {
    run(() => api('DELETE', `/alerts/${t.dataset.delAlert}`));
  } else if (t.dataset.delNote) {
    run(() => api('DELETE', `/notes/${t.dataset.delNote}`));
  } else if (t.dataset.cmd) {
    run(() => api('POST', `/devices/${encodeURIComponent(t.dataset.dev)}/command`, { command: t.dataset.cmd }), 'Sent');
  } else if (t.dataset.preset) {
    run(() => api('POST', '/alerts', { ...PRESETS[t.dataset.preset].body, source: 'web' }), 'Alert sent');
  }
});

$('#clearAlerts').onclick = () => run(() => api('DELETE', '/alerts'), busEnabled ? 'Alerts hidden on the tablets' : 'Alerts cleared');

$('#alertForm').onsubmit = (e) => {
  e.preventDefault();
  const f = e.target.elements;
  const body = {
    title: f.title.value,
    message: f.message.value,
    severity: f.severity.value,
    sound: f.sound.checked,
    source: 'web',
  };
  if (f.duration.value !== '') body.durationSeconds = Number(f.duration.value);
  run(async () => {
    await api('POST', '/alerts', body);
    f.title.value = f.message.value = '';
  }, 'Alert sent');
};

$('#noteForm').onsubmit = (e) => {
  e.preventDefault();
  const input = e.target.elements.text;
  run(async () => {
    await api('POST', '/notes', { text: input.value });
    input.value = '';
  });
};

$('#settingsForm').addEventListener('input', syncSettingsUi);

$('#geoBtn').onclick = () => {
  navigator.geolocation?.getCurrentPosition(
    (p) => {
      const f = $('#settingsForm').elements;
      f.lat.value = p.coords.latitude.toFixed(4);
      f.lon.value = p.coords.longitude.toFixed(4);
    },
    (err) => toast(`Location unavailable: ${err.message}`),
  );
};

$('#settingsForm').onsubmit = (e) => {
  e.preventDefault();
  const f = e.target.elements;
  const patch = {
    units: f.units.value,
    clock24h: f.clock24h.checked,
    radarZoom: Number(f.radarZoom.value),
    brightness: Number(f.brightness.value) === 0 ? null : Number(f.brightness.value) / 100,
    night: {
      enabled: f.nightEnabled.checked,
      useSensor: f.nightUseSensor.checked,
      darkLux: Number(f.nightDarkLux.value),
      start: f.nightStart.value,
      end: f.nightEnd.value,
      brightness: Number(f.nightBrightness.value) / 100,
    },
  };
  if (f.useTablet.checked) patch.location = null;
  else {
    if (f.lat.value === '' || f.lon.value === '') return toast('Enter latitude and longitude');
    patch.location = { lat: Number(f.lat.value), lon: Number(f.lon.value), name: f.locName.value || null };
  }
  run(() => api('PATCH', '/display', patch), 'Settings saved');
};

// --- start ---

$('#presets').innerHTML = PRESETS.map((p, i) => `<button class="ghost small" data-preset="${i}">${esc(p.label)}</button>`).join('');
api('GET', '/health')
  .then((h) => {
    $('#authNote').textContent = h.auth ? 'This server requires an API key (X-API-Key header).' : 'No API key is configured; anyone on the LAN can use the API.';
    // With the MQTT bus, alerts are sent from the Local services hub (port 80 on this PC) so every display gets them.
    if (h.mqtt?.enabled) {
      busEnabled = true;
      $('#alertForm').hidden = $('#presets').hidden = true;
      $('#busNote').hidden = false;
      $('#hubLink').href = `${location.protocol}//${location.hostname}/`;
      $('#clearAlerts').textContent = 'Hide all';
      if (state) renderAlerts();
    }
  })
  .catch(() => {});
renderApiExamples();
connect();
// Keep the "x min left" labels fresh.
setInterval(() => state && (renderAlerts(), renderDevices()), 15000);
