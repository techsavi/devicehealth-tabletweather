import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Store, ValidationError } from '../src/store.js';
import { connectBus } from '../src/mqtt.js';

// A stand-in MQTT client: records what is published, and lets a test deliver messages.
class FakeClient extends EventEmitter {
  published = [];
  publish(topic, payload, opts) { this.published.push({ topic, payload, opts }); }
  subscribe() {}
  end() {}
  deliver(topic, body) { this.emit('message', topic, Buffer.from(body === '' ? '' : JSON.stringify(body))); }
}

async function setup(opts = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tp-mqtt-'));
  const store = new Store(path.join(dir, 'state.json'));
  const client = new FakeClient();
  const bus = await connectBus(store, { client, stateFile: path.join(dir, 'mqtt-state.json'), log: () => {}, ...opts });
  client.emit('connect');
  return { store, client, bus };
}

const alert = (level, extra = {}) => ({ level, title: 'Disk', message: 'Disk / is 91% full', since: 1, ...extra });

test('warnings from the bus reach the tablets under <source>-<id>; info only from manual sources', async () => {
  const { store, client, bus } = await setup();
  client.deliver('alerts/statuspanel/abc', alert('warning'));
  client.deliver('alerts/statuspanel/low', alert('info'));
  client.deliver('alerts/manual/dinner', alert('info', { title: 'Dinner is ready' }));
  const shown = (deviceId) => store.state.alerts.filter(bus.filterFor(deviceId)).map((a) => a.tag).sort();
  assert.deepEqual(shown(null), ['manual-dinner', 'statuspanel-abc'], 'the default view');
  assert.equal(store.state.alerts.length, 3, 'every alert is kept, for tablets set to show all');
  assert.equal(store.findAlert('statuspanel-abc').source, 'statuspanel');
});

test('a tablet follows its "Show alerts" setting from the hub registry', async () => {
  let changes = 0;
  const { store, client, bus } = await setup({ onSettings: () => changes++ });
  client.deliver('alerts/statuspanel/abc', alert('warning'));
  client.deliver('alerts/statuspanel/low', alert('info'));
  client.deliver('alerts/weather/storm', alert('critical'));
  const shown = (deviceId) => store.state.alerts.filter(bus.filterFor(deviceId)).map((a) => a.tag).sort();
  const set = (alerts) => client.deliver('registry/devices/tablet-1234', { name: 'tablet-1234', kind: 'tablet', deviceId: 'uuid-1', ...(alerts ? { alerts } : {}) });

  set('all');
  assert.deepEqual(shown('uuid-1'), ['statuspanel-abc', 'statuspanel-low', 'weather-storm']);
  assert.deepEqual(shown('uuid-2'), ['statuspanel-abc', 'weather-storm'], 'other tablets keep the default');
  set('critical');
  assert.deepEqual(shown('uuid-1'), ['weather-storm']);
  set('off');
  assert.deepEqual(shown('uuid-1'), []);
  set(null);
  assert.deepEqual(shown('uuid-1'), ['statuspanel-abc', 'weather-storm'], 'no setting: the default');
  client.deliver('registry/devices/tablet-1234', '');
  assert.equal(changes, 4, 'each change is reported once, so the tablets get a new state');
});

test('an update chimes only when the level rises', async () => {
  const { store, client } = await setup();
  client.deliver('alerts/statuspanel/abc', alert('warning'));
  const first = store.findAlert('statuspanel-abc').id;
  client.deliver('alerts/statuspanel/abc', alert('warning', { message: 'Disk / is 92% full' }));
  assert.equal(store.findAlert('statuspanel-abc').id, first, 'quiet update keeps the id');
  assert.equal(store.findAlert('statuspanel-abc').message, 'Disk / is 92% full');
  client.deliver('alerts/statuspanel/abc', alert('critical'));
  assert.notEqual(store.findAlert('statuspanel-abc').id, first, 'escalation gets a new id, so tablets chime');
});

test('an empty retained message clears the alert', async () => {
  const { store, client } = await setup();
  client.deliver('alerts/statuspanel/abc', alert('warning'));
  client.deliver('alerts/statuspanel/abc', '');
  assert.equal(store.state.alerts.length, 0);
});

test('dismissing hides a bus alert on the tablets until it escalates', async () => {
  const { store, client, bus } = await setup();
  client.deliver('alerts/statuspanel/abc', alert('warning'));
  assert.ok(bus.hide(store.findAlert('statuspanel-abc').id));
  assert.equal(store.state.alerts.length, 0);
  assert.equal(client.published.filter((p) => p.topic.startsWith('alerts/')).length, 0, 'not cleared on the bus');
  client.deliver('alerts/statuspanel/abc', alert('warning', { message: 'still full' }));
  assert.equal(store.state.alerts.length, 0, 'same level stays hidden');
  client.deliver('alerts/statuspanel/abc', alert('critical'));
  assert.equal(store.findAlert('statuspanel-abc').severity, 'critical');
});

test('POST publishes to alerts/tabletpanel/<tag> with an expiry, and removing it clears it on the bus', async () => {
  const { store, client, bus } = await setup();
  const sent = bus.publish({ title: 'Someone is at the door', severity: 'warning', durationSeconds: 60, tag: 'doorbell' });
  assert.equal(sent.topic, 'alerts/tabletpanel/doorbell');
  const p = client.published.find((x) => x.topic === sent.topic);
  assert.equal(p.opts.retain, true);
  assert.equal(p.opts.properties.messageExpiryInterval, 60);
  const body = JSON.parse(p.payload);
  assert.equal(body.level, 'warning');
  assert.equal(body.expires - body.since, 60);

  client.deliver(sent.topic, body);
  assert.ok(store.findAlert('tabletpanel-doorbell'));
  assert.ok(bus.hide('doorbell'), 'the old tag still works');
  assert.deepEqual(client.published.at(-1), { topic: sent.topic, payload: '', opts: { retain: true, qos: 1 } });
  assert.throws(() => bus.publish({}), ValidationError);
  assert.throws(() => bus.publish({ title: 'x', tag: 'no/slashes' }), ValidationError);
});

test('alerts relayed before a reconnect that the broker no longer holds are dropped', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] });
  const { store, client } = await setup();
  client.deliver('alerts/statuspanel/gone', alert('warning'));
  client.deliver('alerts/statuspanel/kept', alert('warning'));
  t.mock.timers.tick(2000);
  client.emit('connect');
  client.deliver('alerts/statuspanel/kept', alert('warning'));
  t.mock.timers.tick(2000);
  assert.deepEqual(store.state.alerts.map((a) => a.tag), ['statuspanel-kept']);
});
