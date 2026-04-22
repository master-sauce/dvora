'use strict';

const { FFmpeg } = window.FFmpegWASM;
const { fetchFile } = window.FFmpegUtil;

let ffmpeg = null;
let ffmpegReady = false;
let ffmpegLoading = false;

let currentDownload = null;
let abortController = null;

// ── Init ──────────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', init);

async function init() {
  ffmpeg = new FFmpeg();
  loadFFmpeg();
  loadStreams();

  document.getElementById('refreshBtn').addEventListener('click', loadStreams);
  document.getElementById('clearBtn').addEventListener('click', clearStreams);
  document.getElementById('cancelBtn').addEventListener('click', cancelDownload);

  chrome.runtime.onMessage.addListener((msg) => {
    if (msg.action === 'streamDetected') loadStreams();
  });
}

async function loadFFmpeg() {
  if (ffmpegReady || ffmpegLoading) return;
  ffmpegLoading = true;
  try {
    const coreURL = chrome.runtime.getURL('lib/ffmpeg/ffmpeg-core.js');
    const wasmURL = chrome.runtime.getURL('lib/ffmpeg/ffmpeg-core.wasm');
    await ffmpeg.load({ coreURL, wasmURL });
    ffmpegReady = true;
    console.log('FFmpeg ready (local)');
  } catch (err) {
    console.error('FFmpeg failed to load:', err);
    ffmpegLoading = false;
  }
}

// ── Load & display ────────────────────────────────────────────────────────────

async function loadStreams() {
  try {
    const { streams = [] } = await chrome.runtime.sendMessage({ action: 'getStreams' });
    displayStreams(streams);
  } catch (err) {
    console.error('Failed to load streams:', err);
  }
}

function displayStreams(streams) {
  const list = document.getElementById('streamsList');
  const dot = document.getElementById('statsDot');
  const statsText = document.getElementById('statsText');

  const count = streams.length;
  statsText.textContent = count === 0 ? 'no streams detected' : `${count} stream${count !== 1 ? 's' : ''} detected`;
  dot.classList.toggle('active', count > 0);

  if (count === 0) {
    list.innerHTML = `
      <div class="empty-state">
        <div class="empty-icon">📡</div>
        <p>No HLS streams detected yet.</p>
        <span class="hint">Play a video to begin</span>
      </div>`;
    return;
  }

  list.innerHTML = '';
  for (const stream of streams) {
    list.appendChild(buildStreamCard(stream));
  }
}

function buildStreamCard(stream) {
  const isEncrypted = !!stream.encryption;
  const hasVariants = stream.isMaster && stream.variants?.length > 0;
  const segCount = stream.segmentCount ?? stream.segments?.length ?? 0;
  const totalDuration = stream.totalDuration ?? 0;

  const card = document.createElement('div');
  card.className = 'stream-card';
  card.dataset.id = stream.id;

  card.innerHTML = `
    <div class="stream-card-body">
      <div class="stream-top">
        <div class="stream-title" title="${escapeHtml(stream.title || '')}">${escapeHtml(stream.title || 'Unknown Video')}</div>
        <div style="display:flex;gap:4px;flex-shrink:0;flex-wrap:wrap;justify-content:flex-end">
          ${hasVariants ? `<span class="badge badge-master">MASTER</span>` : ''}
          <span class="badge ${isEncrypted ? 'badge-encrypted' : 'badge-clear'}">${isEncrypted ? 'AES-128' : 'CLEAR'}</span>
        </div>
      </div>

      <div class="stream-meta">
        <span class="meta-item">${segCount > 0 ? segCount + ' segs' : 'live/master'}</span>
        ${totalDuration ? `<span class="meta-item">${formatDuration(totalDuration)}</span>` : ''}
        <span class="meta-item">${formatAge(stream.timestamp)}</span>
      </div>

      <div class="segment-track"><div class="segment-track-fill"></div></div>

      ${hasVariants ? buildQualitySelector(stream) : ''}

      <div class="stream-actions">
        <button class="btn btn-ghost btn-sm js-copy" data-id="${stream.id}">Copy URL</button>
        <button class="btn btn-ghost btn-sm js-ffmpeg-cmd" data-id="${stream.id}">ffmpeg cmd</button>
        <button class="btn btn-primary js-download" data-id="${stream.id}">↓ Download MP4</button>
      </div>
    </div>`;

  card.querySelector('.js-copy').addEventListener('click', (e) => handleCopy(e, stream));
  card.querySelector('.js-ffmpeg-cmd').addEventListener('click', (e) => handleFfmpegCmd(e, stream));
  card.querySelector('.js-download').addEventListener('click', () => handleDownload(stream.id));

  return card;
}

function buildQualitySelector(stream) {
  const options = (stream.variants || []).map((v, i) => {
    const label = [v.resolution, v.bandwidth ? formatBitrate(v.bandwidth) : '', v.name].filter(Boolean).join(' · ');
    return `<option value="${i}">${escapeHtml(label || `Variant ${i + 1}`)}</option>`;
  }).join('');
  return `
    <div class="quality-row">
      <div class="quality-label">Quality</div>
      <select class="quality-select" id="variant-${stream.id}">${options}</select>
    </div>`;
}

// ── Actions ───────────────────────────────────────────────────────────────────

async function handleCopy(e, stream) {
  const btn = e.currentTarget;
  const original = btn.textContent;
  try {
    await navigator.clipboard.writeText(stream.url);
    btn.textContent = '✓ Copied';
  } catch { btn.textContent = 'Failed'; }
  setTimeout(() => { btn.textContent = original; }, 1500);
}

async function handleFfmpegCmd(e, stream) {
  const btn = e.currentTarget;
  const original = btn.textContent;
  const url = (stream.isMaster && stream.variants?.length) ? stream.variants[0].url : stream.url;
  const cmd = `ffmpeg -i "${url}" -c copy -bsf:a aac_adtstoasc output.mp4`;
  try {
    await navigator.clipboard.writeText(cmd);
    btn.textContent = '✓ Copied';
  } catch { btn.textContent = 'Failed'; }
  setTimeout(() => { btn.textContent = original; }, 1500);
}

async function handleDownload(streamId) {
  if (currentDownload) {
    alert('A download is already in progress. Please cancel it first.');
    return;
  }
  if (!ffmpegReady) {
    if (!ffmpegLoading) loadFFmpeg();
    alert('FFmpeg is still loading. Please wait a moment and try again.');
    return;
  }

  const variantSelect = document.getElementById(`variant-${streamId}`);
  const variantIndex = variantSelect ? parseInt(variantSelect.value, 10) : 0;

  // Get full stream data from background (includes playlistText)
  const { stream } = await chrome.runtime.sendMessage({ action: 'getStream', streamId });
  if (!stream) { alert('Stream data not found. Try refreshing.'); return; }

  showDownloadPanel();
  setProgress(0, 'Fetching playlist…', '');

  abortController = new AbortController();

  try {
    // ── ALWAYS re-fetch playlist at download time ──
    // Stored segments may be 0 or stale (token-expiry). Re-fetch fresh.
    let targetUrl = stream.url;

    if (stream.isMaster && stream.variants?.length) {
      // Master: pick the selected quality variant URL
      targetUrl = (stream.variants[variantIndex] || stream.variants[0]).url;
    }

    setProgress(2, 'Fetching playlist…', targetUrl.split('/').pop());

    const playlistResult = await fetchPlaylist(targetUrl, abortController.signal);

    if (!playlistResult) {
      throw new Error('Failed to fetch playlist. The stream URL may have expired — try playing the video again and then downloading.');
    }

    const { segments, encryption, initSegment } = playlistResult;

    if (!segments.length) {
      // Give a helpful message with a peek at what we got
      throw new Error(
        `No segments found in playlist.\n\nPlaylist preview:\n${playlistResult.rawText?.substring(0, 400) || '(empty)'}`
      );
    }

    currentDownload = { streamId, stream, total: segments.length, done: 0, failed: 0 };

    await downloadAndConvert({ ...stream, segments, encryption, initSegment });

  } catch (err) {
    if (err.name !== 'AbortError') {
      console.error('Download error:', err);
      alert(`Download failed:\n\n${err.message}`);
    }
  } finally {
    currentDownload = null;
    hideDownloadPanel();
  }
}

// ── Playlist fetching ─────────────────────────────────────────────────────────

async function fetchPlaylist(url, signal) {
  try {
    const resp = await fetch(url, { signal });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const rawText = await resp.text();

    console.log('Fetched playlist, first 500 chars:', rawText.substring(0, 500));

    if (!rawText.includes('#EXTM3U')) return { segments: [], rawText };

    return parseMediaPlaylist(rawText, url);
  } catch (err) {
    if (err.name === 'AbortError') throw err;
    console.error('Playlist fetch failed:', err);
    return null;
  }
}

// ── Download & convert ────────────────────────────────────────────────────────

const CONCURRENT = 6;

async function downloadAndConvert(stream) {
  const { segments, initSegment } = stream;
  const isEncrypted = stream.encryption?.method === 'AES-128';

  setProgress(3, isEncrypted ? 'Fetching decryption keys…' : 'Starting download…', `${segments.length} segments`);

  const keys = isEncrypted ? await fetchKeys(stream) : new Map();
  const signal = abortController.signal;
  const segmentFiles = new Array(segments.length).fill(null);

  // Write fMP4 init segment if present
  if (initSegment) {
    setProgress(4, 'Fetching init segment…', '');
    try {
      const initData = await downloadWithRetry(initSegment, signal);
      await ffmpeg.writeFile('init.mp4', new Uint8Array(initData));
    } catch (e) {
      console.warn('Init segment fetch failed:', e);
    }
  }

  for (let i = 0; i < segments.length; i += CONCURRENT) {
    if (signal.aborted) throw Object.assign(new Error('Cancelled'), { name: 'AbortError' });

    const batch = segments.slice(i, i + CONCURRENT).map((seg, offset) => ({ seg, index: i + offset }));

    await Promise.all(batch.map(async ({ seg, index }) => {
      try {
        let data = await downloadWithRetry(seg.url, signal);
        if (isEncrypted && seg.key?.uri) data = await decryptSegment(data, seg.key, keys);

        const ext = seg.url.match(/\.(m4s|mp4|ts)(\?|$)/i)?.[1] || 'ts';
        const filename = `seg_${String(index).padStart(5, '0')}.${ext}`;
        await ffmpeg.writeFile(filename, new Uint8Array(data));
        segmentFiles[index] = filename;
        currentDownload.done++;
      } catch (err) {
        if (err.name === 'AbortError') throw err;
        console.warn(`Segment ${index} failed:`, err.message);
        currentDownload.failed++;
      }

      const sofar = currentDownload.done + currentDownload.failed;
      setProgress(
        5 + Math.round((sofar / segments.length) * 55),
        'Downloading…',
        `${currentDownload.done} / ${segments.length} · ${currentDownload.failed} failed`
      );
    }));
  }

  const downloaded = segmentFiles.filter(Boolean);
  if (!downloaded.length) throw new Error('No segments downloaded successfully.');
  if (currentDownload.failed / segments.length > 0.15) {
    throw new Error(`Too many segment failures: ${currentDownload.failed}/${segments.length}`);
  }

  setProgress(62, 'Merging…', `${downloaded.length} segments`);

  // If fMP4, prepend init segment to each entry
  const concatEntries = [];
  if (initSegment && await fileExists('init.mp4')) {
    concatEntries.push(`file 'init.mp4'`);
  }
  concatEntries.push(...downloaded.map(f => `file '${f}'`));
  await ffmpeg.writeFile('concat.txt', concatEntries.join('\n'));

  setProgress(65, 'Converting to MP4…', 'This may take a while…');

  ffmpeg.on('progress', ({ progress }) => {
    setProgress(Math.min(65 + Math.round((progress || 0) * 30), 95), 'Converting…');
  });

  const outputName = `${sanitizeFilename(stream.title || 'video')}.mp4`;

  await ffmpeg.exec([
    '-f', 'concat', '-safe', '0',
    '-i', 'concat.txt',
    '-c', 'copy',
    '-bsf:a', 'aac_adtstoasc',
    '-movflags', '+faststart',
    outputName
  ]);

  setProgress(96, 'Saving…', '');
  const data = await ffmpeg.readFile(outputName);

  // Cleanup
  for (const f of downloaded) ffmpeg.deleteFile(f).catch(() => {});
  ffmpeg.deleteFile('concat.txt').catch(() => {});
  ffmpeg.deleteFile('init.mp4').catch(() => {});
  ffmpeg.deleteFile(outputName).catch(() => {});

  const blob = new Blob([data.buffer], { type: 'video/mp4' });
  await chrome.downloads.download({ url: URL.createObjectURL(blob), filename: outputName, saveAs: true });

  setProgress(100, 'Done!', '');
  setTimeout(hideDownloadPanel, 1200);
}

async function fileExists(name) {
  try { await ffmpeg.readFile(name); return true; } catch { return false; }
}

// ── Crypto ────────────────────────────────────────────────────────────────────

async function fetchKeys(stream) {
  const keys = new Map();
  const urls = new Set((stream.segments || []).filter(s => s.key?.uri).map(s => s.key.uri));
  await Promise.all([...urls].map(async (url) => {
    try {
      const resp = await fetch(url);
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      keys.set(url, new Uint8Array(await resp.arrayBuffer()));
      console.log('Key fetched:', url);
    } catch (err) {
      console.error('Key fetch failed:', url, err);
    }
  }));
  return keys;
}

async function decryptSegment(encryptedData, keyInfo, keys) {
  const keyData = keys.get(keyInfo.uri);
  if (!keyData) throw new Error(`Decryption key not found: ${keyInfo.uri}`);

  const cryptoKey = await crypto.subtle.importKey('raw', keyData, { name: 'AES-CBC' }, false, ['decrypt']);

  let iv;
  if (keyInfo.iv) {
    iv = hexToBuffer(keyInfo.iv.padStart(32, '0'));
  } else {
    iv = new Uint8Array(16);
    new DataView(iv.buffer).setUint32(12, keyInfo.sequence || 0, false);
  }

  return crypto.subtle.decrypt({ name: 'AES-CBC', iv }, cryptoKey, encryptedData);
}

// ── Network ───────────────────────────────────────────────────────────────────

async function downloadWithRetry(url, signal, retries = 3) {
  for (let attempt = 0; attempt < retries; attempt++) {
    try {
      const resp = await fetch(url, { signal });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      return await resp.arrayBuffer();
    } catch (err) {
      if (err.name === 'AbortError') throw err;
      if (attempt === retries - 1) throw err;
      await delay(500 * 2 ** attempt);
    }
  }
}

// ── Playlist parsing ──────────────────────────────────────────────────────────

function parseMediaPlaylist(text, baseUrl) {
  const lines = text.split(/\r?\n/);
  const segments = [];
  let currentKey = null;
  let seq = 0;
  let initSegment = null;

  const seqMatch = text.match(/#EXT-X-MEDIA-SEQUENCE:(\d+)/);
  if (seqMatch) seq = parseInt(seqMatch[1], 10);

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line || line === '#EXTM3U') continue;

    // Encryption key
    if (line.startsWith('#EXT-X-KEY')) {
      const methodMatch = line.match(/METHOD=([^,\r\n]+)/);
      const uriMatch = line.match(/URI="([^"]+)"/);
      const ivMatch = line.match(/IV=0x([0-9a-fA-F]+)/);
      currentKey = {
        method: methodMatch ? methodMatch[1].trim() : 'NONE',
        uri: uriMatch ? resolveUrl(uriMatch[1], baseUrl) : null,
        iv: ivMatch ? ivMatch[1] : null,
        sequence: seq
      };
    }

    // fMP4 init segment
    if (line.startsWith('#EXT-X-MAP')) {
      const uriMatch = line.match(/URI="([^"]+)"/);
      if (uriMatch) initSegment = resolveUrl(uriMatch[1], baseUrl);
    }

    // Segment
    if (line.startsWith('#EXTINF')) {
      const durMatch = line.match(/#EXTINF:([^,\r\n]+)/);
      const duration = durMatch ? parseFloat(durMatch[1]) : 0;

      // Find next non-comment line
      let nextLine = null;
      for (let j = i + 1; j < lines.length; j++) {
        const candidate = lines[j].trim();
        if (candidate && !candidate.startsWith('#')) {
          nextLine = candidate;
          break;
        }
      }

      if (nextLine) {
        segments.push({
          url: resolveUrl(nextLine, baseUrl),
          duration,
          key: currentKey?.method !== 'NONE' ? currentKey : null,
          sequence: seq++
        });
      }
    }
  }

  const encryption = currentKey?.method !== 'NONE' ? currentKey : null;
  return { segments, encryption, initSegment, rawText: text };
}

function resolveUrl(url, baseUrl) {
  if (!url) return url;
  if (url.startsWith('http://') || url.startsWith('https://')) return url;
  try {
    const base = new URL(baseUrl);
    if (url.startsWith('/')) return `${base.protocol}//${base.host}${url}`;
    return baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1) + url;
  } catch { return url; }
}

// ── UI helpers ────────────────────────────────────────────────────────────────

function showDownloadPanel() { document.getElementById('downloadPanel').classList.remove('hidden'); }
function hideDownloadPanel() {
  document.getElementById('downloadPanel').classList.add('hidden');
  setProgress(0, 'Downloading', '');
}

function setProgress(pct, title, status) {
  document.getElementById('progressFill').style.width = `${pct}%`;
  document.getElementById('progressPercent').textContent = `${pct}%`;
  if (title !== undefined) document.getElementById('dlTitle').textContent = title;
  if (status !== undefined) document.getElementById('progressStatus').textContent = status;
}

function cancelDownload() { abortController?.abort(); }

async function clearStreams() {
  await chrome.runtime.sendMessage({ action: 'clearStreams' });
  loadStreams();
}

// ── Formatters ────────────────────────────────────────────────────────────────

function formatDuration(secs) {
  if (!secs || isNaN(secs)) return '';
  return `${Math.floor(secs / 60)}:${String(Math.floor(secs % 60)).padStart(2, '0')}`;
}

function formatAge(ts) {
  if (!ts) return '';
  const diff = Math.floor((Date.now() - ts) / 1000);
  if (diff < 60) return `${diff}s ago`;
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
  return `${Math.floor(diff / 3600)}h ago`;
}

function formatBitrate(bps) {
  if (!bps) return '';
  if (bps >= 1e6) return `${(bps / 1e6).toFixed(1)} Mbps`;
  return `${(bps / 1e3).toFixed(0)} kbps`;
}

function sanitizeFilename(name) {
  return name.replace(/[^\w\u00C0-\u024F\u4e00-\u9fa5\-]/gi, '_').substring(0, 100) || 'video';
}

function escapeHtml(text) {
  if (!text) return '';
  const d = document.createElement('div');
  d.textContent = text;
  return d.innerHTML;
}

function hexToBuffer(hex) {
  const bytes = new Uint8Array(Math.ceil(hex.length / 2));
  for (let i = 0; i < hex.length; i += 2) bytes[i / 2] = parseInt(hex.substr(i, 2), 16);
  return bytes;
}

function delay(ms) { return new Promise(r => setTimeout(r, ms)); }