// SPDX-License-Identifier: GPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import { DrivePickerSession, DRIVE_FILE } from './session.mjs';

const config = { clientId: 'test.apps.googleusercontent.com', projectNumber: '123456', apiKey: 'PUBLIC-KEY-FIXTURE', origin: 'http://localhost:8765' };
const title = 'עברית العربية Ελληνικά English e\u0301 🎵.mp3';
function fixture() {
  let options, pickerOptions, prompt, time = 0;
  const calls = [], events = [];
  const fixture = {
    calls, events,
    response: () => new Response(JSON.stringify({ id: 'chosen', name: title, mimeType: 'audio/mpeg', capabilities: { canDownload: true } })),
    session: new DrivePickerSession({
      oauth: { initTokenClient(value) { options = value; return { requestAccessToken(value) { prompt = value; } }; } },
      picker(value) { pickerOptions = value; return () => events.push('dismissed'); },
      fetch: async (...args) => { calls.push(args); return fixture.response(...args); },
      now: () => time, status: value => events.push(value), selected: value => events.push(value),
    }),
    get options() { return options; }, get picker() { return pickerOptions; }, get prompt() { return prompt; },
    advance() { time = 4_000_000; },
    grant() { options.callback({ access_token: 'FAKE-BEARER-SECRET', expires_in: 3600, scope: DRIVE_FILE }); },
    select() { pickerOptions.picked([{ id: 'chosen', name: title }]); },
    connect() { fixture.session.authorize(config); fixture.grant(); fixture.select(); },
  };
  return fixture;
}
test('requests only drive.file with explicit account choice and no inherited scopes', () => {
  const f = fixture(); f.session.authorize(config);
  assert.equal(f.options.scope, DRIVE_FILE); assert.equal(f.options.include_granted_scopes, false);
  assert.deepEqual(f.prompt, { prompt: 'select_account' });
});
test('missing or declined scope cannot open Picker or fetch files', async () => {
  const f = fixture(); f.session.authorize(config);
  f.options.callback({ access_token: 'FAKE-BEARER-SECRET', expires_in: 3600, scope: 'profile' });
  assert.equal(f.picker, undefined);
  await assert.rejects(f.session.metadata('chosen'), /Select/); assert.equal(f.calls.length, 0);
});
test('late OAuth and Picker callbacks cannot restore a cleared account session', () => {
  const f = fixture(); f.session.authorize(config); const oldCallback = f.options.callback;
  f.session.clear(); oldCallback({ access_token: 'old', expires_in: 3600, scope: DRIVE_FILE });
  assert.equal(f.picker, undefined);
  f.connect(); const oldPicker = f.picker; f.session.clear(); const count = f.events.length;
  oldPicker.picked([{ id: 'chosen', name: title }]); assert.equal(f.events.length, count);
});
test('Unicode stays exact and token is only in Authorization, never URL, visible state or serialized session', async () => {
  const f = fixture(); f.connect();
  const item = await f.session.metadata('chosen'); assert.equal(item.name, title);
  const [url, request] = f.calls[0];
  assert.equal(new URL(url).hostname, 'www.googleapis.com');
  assert.equal(request.headers.Authorization, 'Bearer FAKE-BEARER-SECRET');
  for (const text of [url, JSON.stringify(f.events), JSON.stringify(f.session), JSON.stringify(item)]) assert.ok(!text.includes('FAKE-BEARER-SECRET'));
  assert.equal(request.redirect, 'error'); assert.equal(request.credentials, 'omit'); assert.equal(request.cache, 'no-store');
});
test('expiry fails before issuing HTTP and requires a new user gesture', async () => {
  const f = fixture(); f.connect(); f.advance();
  await assert.rejects(f.session.metadata('chosen'), /Authorize again/); assert.equal(f.calls.length, 0);
});
test('a selected identifier is not proof of server authorization; 403 remains a server error', async () => {
  const f = fixture(); f.connect(); f.response = () => new Response('FAKE-BEARER-SECRET', { status: 403 });
  await assert.rejects(f.session.metadata('chosen'), { message: 'Metadata HTTP 403.' });
});
test('negative control actually reaches Drive rather than passing a local selection check', async () => {
  const f = fixture(); f.connect(); f.response = () => new Response('', { status: 404 });
  await assert.rejects(f.session.metadata('never-picked', true), /HTTP 404/);
  assert.ok(f.calls[0][0].includes('/never-picked?'));
});
test('unexpected access to a negative control is reported as an unpassed control', async () => {
  const f = fixture(); f.connect();
  const result = await f.session.metadata('never-picked', true);
  assert.equal(result.accessible, true); assert.match(result.warning, /NOT passed/);
});
test('206 validates offset and reads a bounded body', async () => {
  const f = fixture(); f.connect(); f.response = () => new Response(new Uint8Array(16), { status: 206, headers: { 'Content-Range': 'bytes 100-115/500' } });
  assert.deepEqual(await f.session.probe('chosen', 100, 16), { status: 206, contentRange: 'bytes 100-115/500', bytesRead: 16 });
  assert.equal(f.calls[0][1].headers.Range, 'bytes=100-115');
});
test('206 can end at EOF but cannot start at the wrong offset or lack Content-Range', async () => {
  const f = fixture(); f.connect(); f.response = () => new Response(new Uint8Array(5), { status: 206, headers: { 'Content-Range': 'bytes 100-104/105' } });
  assert.equal((await f.session.probe('chosen', 100, 16)).bytesRead, 5);
  for (const headers of [{}, { 'Content-Range': 'bytes 0-15/500' }, { 'Content-Range': 'bytes 100-115/115' }]) {
    f.response = () => new Response(new Uint8Array(16), { status: 206, headers });
    await assert.rejects(f.session.probe('chosen', 100, 16), /honor/);
  }
});
test('200 at zero is bounded and 200 after a seek is rejected before reading', async () => {
  const f = fixture(); f.connect(); f.response = () => new Response(new Uint8Array(100), { status: 200 });
  assert.equal((await f.session.probe('chosen', 0, 16)).bytesRead, 16);
  await assert.rejects(f.session.probe('chosen', 100, 16), /honor/);
});
test('401 clears memory and does not loop or launch another OAuth popup automatically', async () => {
  const f = fixture(); f.connect(); f.response = () => new Response('FAKE-BEARER-SECRET', { status: 401 });
  await assert.rejects(f.session.probe('chosen'), { message: 'HTTP 401; authorize again.' });
  await assert.rejects(f.session.metadata('chosen'), /Select/); assert.equal(f.calls.length, 1);
});
test('403 and connectivity errors never reflect server bodies, raw errors or tokens', async () => {
  const f = fixture(); f.connect(); f.response = () => new Response('FAKE-BEARER-SECRET', { status: 403 });
  await assert.rejects(f.session.probe('chosen'), { message: 'Media HTTP 403.' });
  f.response = () => { throw new Error('network detail FAKE-BEARER-SECRET'); };
  await assert.rejects(f.session.probe('chosen'), { message: 'Drive request failed; check connectivity/CORS.' });
});
test('a response from a previous account cannot update the new account session', async () => {
  const f = fixture(); f.connect(); let resolve;
  f.response = () => new Promise(r => { resolve = r; });
  const request = f.session.metadata('chosen'); f.session.clear();
  resolve(new Response(JSON.stringify({ id: 'chosen', name: 'stale account data' })));
  await assert.rejects(request, /Session changed/);
});
test('arbitrary URLs and excessive ranges cannot reach the token broker or HTTP', async () => {
  const f = fixture(); f.connect();
  await assert.rejects(f.session.metadata('https://example.invalid/'), /Select/);
  await assert.rejects(f.session.probe('chosen', 0, 1_000_000), /Invalid bounded/);
  assert.equal(f.calls.length, 0);
});
test('truncated partial response fails instead of being reported as a successful seek', async () => {
  const f = fixture(); f.connect();
  f.response = () => new Response(new Uint8Array(3), { status: 206, headers: { 'Content-Range': 'bytes 100-115/500' } });
  await assert.rejects(f.session.probe('chosen', 100, 16), /Truncated/);
});
test('read failures do not expose a raw stream error containing a credential', async () => {
  const f = fixture(); f.connect();
  f.response = () => new Response(new ReadableStream({ start(controller) { controller.error(new Error('FAKE-BEARER-SECRET')); } }));
  await assert.rejects(f.session.probe('chosen'), { message: 'Drive stream interrupted or session changed.' });
});
