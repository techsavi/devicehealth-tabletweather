import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Store, ValidationError } from '../src/store.js';

const tmpStore = () => new Store(path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'tp-')), 'state.json'));

test('alerts get severity defaults and expire', () => {
  const s = tmpStore();
  const info = s.addAlert({ message: 'hi' });
  assert.equal(info.severity, 'info');
  assert.equal(info.sound, false);
  assert.ok(info.expiresAt);
  const crit = s.addAlert({ title: 'Fire', severity: 'critical' });
  assert.equal(crit.expiresAt, null);
  assert.equal(crit.sound, true);

  s.state.alerts[0].expiresAt = new Date(Date.now() - 1000).toISOString();
  s.sweep();
  assert.deepEqual(s.state.alerts.map((a) => a.title), ['Fire']);
});

test('a repeated tag replaces the earlier alert and can be removed by tag', () => {
  const s = tmpStore();
  s.addAlert({ title: 'Ding', tag: 'door' });
  s.addAlert({ title: 'Ding again', tag: 'door' });
  assert.equal(s.state.alerts.length, 1);
  assert.ok(s.removeAlert('door'));
  assert.equal(s.state.alerts.length, 0);
});

test('invalid input is rejected without partial updates', () => {
  const s = tmpStore();
  assert.throws(() => s.addAlert({}), ValidationError);
  assert.throws(() => s.addAlert({ message: 'x', severity: 'loud' }), ValidationError);
  assert.throws(() => s.updateDisplay({ units: 'metric', focus: 'nope' }), ValidationError);
  assert.equal(s.state.display.units, 'imperial');
});

test('a timed focus reverts to auto', () => {
  const s = tmpStore();
  s.updateDisplay({ focus: 'radar', focusMinutes: 15 });
  assert.equal(s.state.display.focus, 'radar');
  s.state.display.focusUntil = new Date(Date.now() - 1).toISOString();
  s.sweep();
  assert.equal(s.state.display.focus, 'auto');
  assert.equal(s.state.display.focusUntil, null);
});

test('state survives a restart', () => {
  const s = tmpStore();
  s.updateDisplay({ units: 'metric', location: { lat: 1, lon: 2, name: 'Here' } });
  s.addNote({ text: 'Trash day' });
  const again = new Store(s.file);
  assert.equal(again.state.display.units, 'metric');
  assert.equal(again.state.display.location.name, 'Here');
  assert.equal(again.state.notes[0].text, 'Trash day');
});
