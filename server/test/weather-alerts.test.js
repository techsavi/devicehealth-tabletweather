import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Store, ValidationError } from '../src/store.js';
import { connectBus } from '../src/mqtt.js';
import { startWeatherAlerts, levelOf } from '../src/weather-alerts.js';

// A stand-in MQTT client that loops publishes back, like the broker does for our subscription.
class LoopbackClient extends EventEmitter {
  published = [];
  publish(topic, payload, opts) {
    this.published.push({ topic, payload, opts });
    this.emit('message', topic, Buffer.from(payload));
  }
  subscribe() {}
  end() {}
}

const hour = 3600_000;
const nws = (id, extra = {}) => ({
  properties: {
    id, event: 'Tornado Warning', headline: 'Tornado Warning until 11:00PM EDT', areaDesc: 'Orange, FL',
    severity: 'Extreme', urgency: 'Immediate', status: 'Actual', messageType: 'Alert',
    sent: new Date(Date.now() - hour).toISOString(), expires: new Date(Date.now() + hour).toISOString(),
    ...extra,
  },
});

async function setup(points = [{ lat: 28.4294, lon: -81.3089 }], busOpts = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tp-wx-'));
  const store = new Store(path.join(dir, 'state.json'));
  const client = new LoopbackClient();
  const bus = await connectBus(store, { client, stateFile: path.join(dir, 'mqtt-state.json'), log: () => {}, ...busOpts });
  client.emit('connect');
  const feed = { features: [], fail: false, urls: [] };
  const fetch = async (url) => {
    feed.urls.push(url);
    if (feed.fail) return { ok: false, status: 503 };
    return { ok: true, json: async () => ({ features: structuredClone(feed.features) }) };
  };
  const wx = startWeatherAlerts(bus, { points: () => points, fetch, stateFile: path.join(dir, 'weather-state.json'), log: () => {} });
  wx.stop(); // tests poll by hand
  return { store, client, bus, feed, wx, alertTopics: () => client.published.filter((p) => p.topic.startsWith('alerts/')) };
}

test('an NWS alert is published on the bus and reaches the tablets; minor ones stay on the bus', async () => {
  const { store, feed, wx, alertTopics, bus } = await setup();
  feed.features = [nws('urn:a'), nws('urn:b', { event: 'Special Weather Statement', severity: 'Minor' })];
  await wx.poll();
  assert.match(feed.urls[0], /point=28\.429,-81\.309&status=actual$/);
  const [p] = alertTopics();
  assert.match(p.topic, /^alerts\/tabletpanel\/nws-[0-9a-f]{12}$/);
  assert.equal(p.opts.retain, true);
  assert.ok(p.opts.properties.messageExpiryInterval > 3500);
  const body = JSON.parse(p.payload);
  assert.equal(body.level, 'critical');
  assert.equal(body.title, 'Tornado Warning');
  assert.equal(alertTopics().length, 2, 'the minor one is published too');
  assert.deepEqual(store.state.alerts.filter(bus.filterFor(null)).map((a) => [a.severity, a.source]), [['critical', 'weather']], 'only the warning is shown');
  assert.equal(wx.status().active, 2);

  const all = await setup(undefined, { allLevelSources: ['manual', 'tabletpanel', 'weather'] });
  all.feed.features = feed.features;
  await all.wx.poll();
  assert.equal(all.store.state.alerts.filter(all.bus.filterFor(null)).length, 2, 'ALERT_ALL_LEVEL_SOURCES=...,weather shows advisories too');
});

test('only an extreme threat happening now is critical (the hub\'s "Severe weather" level)', () => {
  const cases = [
    ['Tornado Warning', 'Extreme', 'Immediate', 'critical'],
    ['Extreme Wind Warning', 'Extreme', 'Immediate', 'critical'],
    ['Hurricane Warning', 'Extreme', 'Expected', 'warning'],
    ['Severe Thunderstorm Warning', 'Severe', 'Immediate', 'warning'],
    ['Tornado Watch', 'Severe', 'Future', 'warning'],
    ['Winter Storm Watch', 'Moderate', 'Future', 'warning'],
    ['Heat Advisory', 'Moderate', 'Expected', 'info'],
    ['Special Weather Statement', 'Moderate', 'Expected', 'info'],
    ['Rip Current Statement', 'Minor', 'Expected', 'info'],
  ];
  for (const [event, severity, urgency, level] of cases) assert.equal(levelOf({ event, severity, urgency }), level, event);
});

test('an update stays on its topic, an unchanged poll publishes nothing, and an ended alert is cleared', async () => {
  const { store, feed, wx, alertTopics } = await setup();
  feed.features = [nws('urn:a', { event: 'Severe Thunderstorm Warning', severity: 'Severe' })];
  await wx.poll();
  const topic = alertTopics()[0].topic;
  const first = store.state.alerts[0].id;
  await wx.poll();
  assert.equal(alertTopics().length, 1, 'nothing new to publish');

  feed.features = [nws('urn:a2', {
    event: 'Severe Thunderstorm Warning', severity: 'Severe', headline: 'extended until midnight',
    sent: new Date().toISOString(), references: [{ identifier: 'urn:a' }],
  })];
  await wx.poll();
  assert.equal(alertTopics().at(-1).topic, topic);
  assert.equal(store.state.alerts.length, 1);
  assert.equal(store.state.alerts[0].message, 'extended until midnight');
  assert.equal(store.state.alerts[0].id, first, 'same level: updated without a chime');

  feed.features = [];
  await wx.poll();
  assert.deepEqual(alertTopics().at(-1), { topic, payload: '', opts: { retain: true, qos: 1 } });
  assert.equal(store.state.alerts.length, 0);
});

test('a failed poll keeps what is published; no location means no request', async () => {
  const { store, feed, wx } = await setup();
  feed.features = [nws('urn:a')];
  await wx.poll();
  feed.fail = true;
  await wx.poll();
  assert.equal(store.state.alerts.length, 1);
  assert.match(wx.status().error, /503/);

  const none = await setup([]);
  await none.wx.poll();
  assert.equal(none.feed.urls.length, 0);
  assert.match(none.wx.status().error, /no location/);
});

test('dismissing a weather alert hides it on the tablets only, and POST cannot use the nws- prefix', async () => {
  const { store, feed, wx, bus, alertTopics } = await setup();
  feed.features = [nws('urn:a')];
  await wx.poll();
  assert.ok(bus.hide(store.state.alerts[0].id));
  assert.equal(store.state.alerts.length, 0);
  assert.equal(alertTopics().length, 1, 'not cleared on the bus');
  assert.throws(() => bus.publish({ title: 'x', tag: 'nws-123' }), ValidationError);
});
