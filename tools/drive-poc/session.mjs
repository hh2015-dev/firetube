// SPDX-License-Identifier: GPL-3.0-only
// Experimental browser-only contract. No Android account binding or production token store.
export const DRIVE_FILE = 'https://www.googleapis.com/auth/drive.file';
export const AUDIO_TYPES = 'audio/mpeg,audio/mp3,audio/mp4,audio/x-m4a,audio/flac,audio/x-flac,audio/ogg,application/ogg,audio/wav,audio/x-wav,audio/vnd.wave';

export function rangeMatches(value, start, length) {
  const match = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(value ?? '');
  if (!match) return false;
  const [first, last, total] = match.slice(1).map(Number);
  return [first, last, total].every(Number.isSafeInteger) && first === start && last >= first &&
    total > last && last === Math.min(start + length - 1, total - 1);
}

export class DrivePickerSession {
  #token = null;
  #expires = 0;
  #generation = 0;
  #selected = new Set();
  #dismiss = null;
  constructor({ oauth, picker, fetch = globalThis.fetch, now = Date.now, status = () => {}, selected = () => {} }) {
    this.dependencies = { oauth, picker, fetch, now, status, selected };
  }
  clear() {
    this.#generation++;
    this.#token = null;
    this.#expires = 0;
    this.#selected.clear();
    this.#dismiss?.();
    this.#dismiss = null;
    this.dependencies.selected([]);
  }
  authorize({ clientId, projectNumber, apiKey, origin }) {
    if (!clientId.endsWith('.apps.googleusercontent.com') || !/^\d+$/.test(projectNumber) || !apiKey ||
        !/^https:\/\//.test(origin) && !/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(origin)) {
      throw new Error('Use public Cloud configuration and HTTPS or a localhost test origin.');
    }
    this.clear();
    const generation = this.#generation;
    const { oauth, picker, now, status, selected } = this.dependencies;
    const fail = () => { if (generation === this.#generation) { this.clear(); status('Authorization did not complete.'); } };
    const client = oauth.initTokenClient({
      client_id: clientId, scope: DRIVE_FILE, include_granted_scopes: false,
      error_callback: fail,
      callback: response => {
        if (generation !== this.#generation) return;
        if (response.error || !response.access_token || !response.scope?.split(/\s+/).includes(DRIVE_FILE) ||
            !(Number(response.expires_in) > 0)) { fail(); return; }
        this.#token = response.access_token;
        this.#expires = now() + Number(response.expires_in) * 1000 - 5000;
        try {
          this.#dismiss = picker({ token: this.#token, projectNumber, apiKey, origin, mimeTypes: AUDIO_TYPES,
            picked: files => {
              if (generation !== this.#generation || !this.#token) return;
              const safe = files.filter(f => /^[\w-]+$/.test(f.id)).map(f => ({ id: f.id, name: String(f.name ?? '') }));
              this.#selected = new Set(safe.map(f => f.id));
              selected(safe); // Only IDs/names leave the session; never the raw Picker response.
              status('Selected. Verify metadata and Range using the buttons below. Android grant sharing remains untested.');
            },
          });
        } catch { fail(); }
      },
    });
    client.requestAccessToken({ prompt: 'select_account' });
  }
  async #get(id, query, headers = {}) {
    if (!/^[\w-]+$/.test(id)) throw new Error('Invalid test file ID.');
    if (!this.#token || this.dependencies.now() >= this.#expires) {
      this.clear(); throw new Error('Authorize again using the button.');
    }
    const generation = this.#generation;
    let response;
    try {
      response = await this.dependencies.fetch(`https://www.googleapis.com/drive/v3/files/${encodeURIComponent(id)}?${query}`, {
        headers: { ...headers, Authorization: `Bearer ${this.#token}` },
        credentials: 'omit', redirect: 'error', referrerPolicy: 'no-referrer', cache: 'no-store',
      });
    } catch { throw new Error('Drive request failed; check connectivity/CORS.'); }
    if (generation !== this.#generation) { await response.body?.cancel(); throw new Error('Session changed.'); }
    if (response.status === 401) { await response.body?.cancel(); this.clear(); throw new Error('HTTP 401; authorize again.'); }
    return response;
  }
  async metadata(id, negativeControl = false) {
    if (!negativeControl && !this.#selected.has(id)) throw new Error('Select this file first.');
    const response = await this.#get(id, 'fields=id,name,mimeType,size,capabilities(canDownload)');
    if (!response.ok) { await response.body?.cancel(); throw new Error(`Metadata HTTP ${response.status}.`); }
    if (negativeControl) { await response.body?.cancel(); return { accessible: true, warning: 'File was accessible: check prior grants; negative control has NOT passed.' }; }
    const generation = this.#generation;
    let item;
    try { item = await response.json(); } catch { throw new Error('Invalid metadata.'); }
    if (generation !== this.#generation) throw new Error('Session changed.');
    return { id: item.id, name: item.name, mimeType: item.mimeType, size: item.size, canDownload: item.capabilities?.canDownload };
  }
  async probe(id, start = 0, length = 4096) {
    if (!this.#selected.has(id)) throw new Error('Select this file first.');
    if (!Number.isSafeInteger(start) || start < 0 || !Number.isInteger(length) || length < 1 || length > 4096 ||
        !Number.isSafeInteger(start + length)) throw new Error('Invalid bounded byte range.');
    const response = await this.#get(id, 'alt=media', { Range: `bytes=${start}-${start + length - 1}` });
    if (![200, 206].includes(response.status)) { await response.body?.cancel(); throw new Error(`Media HTTP ${response.status}.`); }
    if (response.status === 200 && start !== 0 || response.status === 206 && !rangeMatches(response.headers.get('Content-Range'), start, length)) {
      await response.body?.cancel(); throw new Error('Server did not honor the requested byte range.');
    }
    const expected = response.status === 206 ? Number(response.headers.get('Content-Range').split(/[-/]/)[1]) - start + 1 : null;
    const reader = response.body?.getReader();
    if (!reader) throw new Error('No readable response body.');
    let bytes = 0;
    const generation = this.#generation;
    try {
      while (bytes < length) {
        const chunk = await reader.read();
        if (generation !== this.#generation) throw new Error('Session changed.');
        if (chunk.done) break;
        bytes += Math.min(chunk.value.length, length - bytes);
      }
    } catch { throw new Error('Drive stream interrupted or session changed.'); }
    finally { await reader.cancel().catch(() => {}); }
    // A browser/proxy may prefetch beyond this limit. This is a probe, not a network-byte quota.
    if (expected !== null && bytes !== expected) throw new Error('Truncated byte range.');
    return { status: response.status, contentRange: response.headers.get('Content-Range'), bytesRead: bytes };
  }
}
