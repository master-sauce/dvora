const streamStore = new Map();
const seenUrls = new Set();
const tabRequests = new Map(); // tabId -> Set of recent request headers

console.log('Background script started');

// ── Request interception ───────────────────────────────────────────────────────

chrome.webRequest.onBeforeRequest.addListener(
  (details) => {
    const url = details.url;
    const urlLower = url.toLowerCase();

    // 1. HLS — classic .m3u8 and common variants
    if (isM3U8Url(urlLower)) {
      captureM3U8(details);
      return;
    }

    // 2. DASH — MPD manifests
    if (isMpdUrl(urlLower)) {
      captureMPD(details);
      return;
    }

    // 3. Direct video files (MP4, WebM, MKV, AVI, MOV, TS)
    if (isDirectVideoUrl(urlLower)) {
      captureDirectVideo(details);
      return;
    }
  },
  {
    urls: ['<all_urls>'],
    types: ['main_frame', 'sub_frame', 'stylesheet', 'script', 'image', 'object', 'xmlhttprequest', 'other', 'media']
  },
  ['requestBody']
);

// 4. Sniff Content-Type headers to catch playlists served without .m3u8 extension
chrome.webRequest.onHeadersReceived.addListener(
  (details) => {
    const ct = details.responseHeaders?.find(h => h.name.toLowerCase() === 'content-type')?.value || '';
    const url = details.url;
    const urlLower = url.toLowerCase();

    // Already handled by URL pattern
    if (isM3U8Url(urlLower) || isMpdUrl(urlLower)) return;

    if (
      ct.includes('mpegurl') ||          // application/vnd.apple.mpegurl, application/x-mpegurl
      ct.includes('x-mpegurl') ||
      ct.includes('application/hls') ||
      ct.includes('x-hls')
    ) {
      console.log('HLS detected via Content-Type:', url, ct);
      captureM3U8(details);
      return;
    }

    if (
      ct.includes('dash+xml') ||         // application/dash+xml
      ct.includes('mpd')
    ) {
      console.log('DASH detected via Content-Type:', url, ct);
      captureMPD(details);
      return;
    }

    // Catch video served as octet-stream or text/plain with video-like URL patterns
    if (
      ct.includes('octet-stream') || ct.includes('text/plain')
    ) {
      if (looksLikePlaylistUrl(urlLower)) {
        console.log('Possible playlist via octet-stream/text/plain:', url);
        captureM3U8(details); // will validate #EXTM3U before storing
      }
    }
  },
  { urls: ['<all_urls>'] },
  ['responseHeaders']
);

// ── URL pattern helpers ───────────────────────────────────────────────────────

function isM3U8Url(url) {
  if (url.includes('.m3u8')) return true;
  if (url.includes('m3u8')) return true;
  // Common path patterns even without extension
  if (/\/(playlist|manifest|index|master|video|hls|stream)(\.php|\.aspx|\.m3u8)?(\?|$|\/)/i.test(url)) {
    return looksLikePlaylistUrl(url);
  }
  return false;
}

function isMpdUrl(url) {
  if (url.includes('.mpd')) return true;
  if (url.includes('manifest.mpd') || url.includes('/dash/') || url.includes('dash.xml')) return true;
  if (/\/(manifest|mpd)(\.php|\.aspx|\.xml)?(\?|$)/i.test(url)) return true;
  return false;
}

function isDirectVideoUrl(url) {
  return /\.(mp4|webm|mkv|avi|mov|flv|wmv|ogv|m4v)($|\?)/i.test(url);
}

function looksLikePlaylistUrl(url) {
  return (
    url.includes('playlist') ||
    url.includes('manifest') ||
    url.includes('/hls/') ||
    url.includes('/dash/') ||
    url.includes('/stream') ||
    url.includes('/video') ||
    url.includes('/media') ||
    url.includes('index.m3u') ||
    url.includes('master.m3u')
  );
}

// ── M3U8 capture ─────────────────────────────────────────────────────────────

async function captureM3U8(details) {
  const normalizedUrl = normalizeUrl(details.url);
  if (seenUrls.has(normalizedUrl)) return;
  seenUrls.add(normalizedUrl);

  console.log('Processing M3U8:', details.url);

  try {
    // Fetch with same Origin/Referer headers the page used, to avoid 403s
    const headers = buildFetchHeaders(details);
    const response = await fetch(details.url, { headers });
    if (!response.ok) {
      console.warn('M3U8 fetch failed:', response.status, details.url);
      seenUrls.delete(normalizedUrl);
      return;
    }
    const text = await response.text();
    console.log('M3U8 preview:', text.substring(0, 300));

    if (!text.includes('#EXTM3U')) {
      // Might still be a redirect — log but don't store
      console.log('Not #EXTM3U, skipping');
      seenUrls.delete(normalizedUrl);
      return;
    }

    const isMaster = text.includes('#EXT-X-STREAM-INF') || text.includes('#EXT-X-MEDIA');
    const title = await getPageTitle(details.tabId);

    const streamData = {
      id: 'stream_' + Date.now() + '_' + Math.random().toString(36).slice(2, 7),
      url: details.url,
      timestamp: Date.now(),
      tabId: details.tabId,
      type: 'HLS',
      isMaster,
      title,
      playlistText: text,
      segments: [],
      variants: [],
      segmentCount: 0,
      totalDuration: 0,
      encryption: null,
      // Store request headers so popup can replay them if needed
      fetchHeaders: headers
    };

    if (isMaster) {
      parseMasterPlaylist(text, details.url, streamData);
    } else {
      parseMediaPlaylist(text, details.url, streamData);
    }

    if (!isMaster && isChildOfKnownMaster(details.url)) {
      console.log('Skipping child playlist covered by master');
      return;
    }

    console.log(`Stored HLS: ${streamData.id} (${isMaster ? streamData.variants.length + ' variants' : streamData.segments.length + ' segments'})`);

    storeStream(streamData);

  } catch (error) {
    console.error('M3U8 capture failed:', error);
    seenUrls.delete(normalizedUrl);
  }
}

// ── MPD / DASH capture ────────────────────────────────────────────────────────

async function captureMPD(details) {
  const normalizedUrl = normalizeUrl(details.url);
  if (seenUrls.has(normalizedUrl)) return;
  seenUrls.add(normalizedUrl);

  console.log('Processing MPD:', details.url);

  try {
    const headers = buildFetchHeaders(details);
    const response = await fetch(details.url, { headers });
    if (!response.ok) { seenUrls.delete(normalizedUrl); return; }
    const text = await response.text();

    if (!text.includes('<MPD') && !text.includes('urn:mpeg:dash')) {
      seenUrls.delete(normalizedUrl);
      return;
    }

    const title = await getPageTitle(details.tabId);
    const parsed = parseMPD(text, details.url);

    const streamData = {
      id: 'stream_' + Date.now() + '_' + Math.random().toString(36).slice(2, 7),
      url: details.url,
      timestamp: Date.now(),
      tabId: details.tabId,
      type: 'DASH',
      isMaster: true,
      title,
      playlistText: text,
      segments: [],
      variants: parsed.variants,
      segmentCount: 0,
      totalDuration: parsed.totalDuration,
      encryption: parsed.isEncrypted ? { method: 'DRM' } : null,
      fetchHeaders: headers
    };

    console.log(`Stored DASH: ${streamData.id} (${parsed.variants.length} representations)`);
    storeStream(streamData);

  } catch (err) {
    console.error('MPD capture failed:', err);
    seenUrls.delete(normalizedUrl);
  }
}

function parseMPD(text, baseUrl) {
  const variants = [];
  let totalDuration = 0;
  let isEncrypted = false;

  if (text.includes('ContentProtection') || text.includes('cenc') || text.includes('widevine')) {
    isEncrypted = true;
  }

  // Extract duration from mediaPresentationDuration
  const durMatch = text.match(/mediaPresentationDuration="PT([\d.]+H)?([\d.]+M)?([\d.]+S)?"/);
  if (durMatch) {
    const h = parseFloat(durMatch[1]) || 0;
    const m = parseFloat(durMatch[2]) || 0;
    const s = parseFloat(durMatch[3]) || 0;
    totalDuration = h * 3600 + m * 60 + s;
  }

  // Extract AdaptationSets with video
  const adaptationSets = [...text.matchAll(/<AdaptationSet([^>]*)>([\s\S]*?)<\/AdaptationSet>/g)];
  for (const [, attrs, content] of adaptationSets) {
    const mimeType = (attrs.match(/mimeType="([^"]+)"/) || content.match(/mimeType="([^"]+)"/))?.[1] || '';
    if (!mimeType.includes('video') && !attrs.includes('video') && !content.includes('video/')) continue;

    const representations = [...content.matchAll(/<Representation([^>]*)>/g)];
    for (const [, repAttrs] of representations) {
      const bandwidth = parseInt(repAttrs.match(/bandwidth="(\d+)"/)?.[1] || '0');
      const width = repAttrs.match(/width="(\d+)"/)?.[1];
      const height = repAttrs.match(/height="(\d+)"/)?.[1];
      const id = repAttrs.match(/id="([^"]+)"/)?.[1] || '';

      // Try to find base URL for this representation
      const baseUrlMatch = content.match(/<BaseURL>([^<]+)<\/BaseURL>/);
      const segUrl = baseUrlMatch ? resolveUrl(baseUrlMatch[1], baseUrl) : baseUrl;

      variants.push({
        url: segUrl,
        bandwidth,
        resolution: (width && height) ? `${width}x${height}` : null,
        codecs: repAttrs.match(/codecs="([^"]+)"/)?.[1] || null,
        name: id,
        dashId: id
      });
    }
  }

  variants.sort((a, b) => (b.bandwidth || 0) - (a.bandwidth || 0));
  return { variants, totalDuration, isEncrypted };
}

// ── Direct video capture ──────────────────────────────────────────────────────

async function captureDirectVideo(details) {
  const normalizedUrl = normalizeUrl(details.url);
  if (seenUrls.has(normalizedUrl)) return;
  seenUrls.add(normalizedUrl);

  const title = await getPageTitle(details.tabId);
  const ext = details.url.match(/\.(mp4|webm|mkv|avi|mov|flv|wmv|ogv|m4v)/i)?.[1]?.toUpperCase() || 'VIDEO';

  const streamData = {
    id: 'stream_' + Date.now() + '_' + Math.random().toString(36).slice(2, 7),
    url: details.url,
    timestamp: Date.now(),
    tabId: details.tabId,
    type: ext,
    isMaster: false,
    isDirectVideo: true,
    title,
    playlistText: null,
    segments: [{ url: details.url, duration: 0, key: null, sequence: 0 }],
    variants: [],
    segmentCount: 1,
    totalDuration: 0,
    encryption: null
  };

  console.log(`Stored direct video: ${streamData.id} (${ext})`);
  storeStream(streamData);
}

// ── Playlist parsers ──────────────────────────────────────────────────────────

function parseMasterPlaylist(text, baseUrl, streamData) {
  const lines = text.split(/\r?\n/);

  // Also parse EXT-X-MEDIA for audio tracks info
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (line.startsWith('#EXT-X-STREAM-INF')) {
      const bandwidthMatch = line.match(/BANDWIDTH=(\d+)/);
      const resolutionMatch = line.match(/RESOLUTION=(\d+x\d+)/);
      const codecsMatch = line.match(/CODECS="([^"]+)"/);
      const nameMatch = line.match(/NAME="([^"]+)"/);
      const frameRateMatch = line.match(/FRAME-RATE=([\d.]+)/);

      // Find next non-comment, non-empty line
      let nextLine = null;
      for (let j = i + 1; j < lines.length; j++) {
        const c = lines[j].trim();
        if (c && !c.startsWith('#')) { nextLine = c; break; }
      }

      if (nextLine) {
        streamData.variants.push({
          url: resolveUrl(nextLine, baseUrl),
          bandwidth: bandwidthMatch ? parseInt(bandwidthMatch[1]) : null,
          resolution: resolutionMatch ? resolutionMatch[1] : null,
          codecs: codecsMatch ? codecsMatch[1] : null,
          name: nameMatch ? nameMatch[1] : null,
          frameRate: frameRateMatch ? parseFloat(frameRateMatch[1]) : null
        });
      }
    }
  }

  streamData.variants.sort((a, b) => (b.bandwidth || 0) - (a.bandwidth || 0));
}

function parseMediaPlaylist(text, baseUrl, streamData) {
  const lines = text.split(/\r?\n/);
  let currentKey = null;
  let seq = 0;
  let byteRangeOffset = null;
  let byteRangeBaseUrl = null;

  const seqMatch = text.match(/#EXT-X-MEDIA-SEQUENCE:(\d+)/);
  if (seqMatch) seq = parseInt(seqMatch[1]);

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line) continue;

    if (line.startsWith('#EXT-X-KEY')) {
      const methodMatch = line.match(/METHOD=([^,\r\n]+)/);
      const uriMatch = line.match(/URI="([^"]+)"/);
      const ivMatch = line.match(/IV=0x([0-9a-fA-F]+)/);
      const method = methodMatch ? methodMatch[1].trim() : 'NONE';
      currentKey = {
        method,
        uri: uriMatch ? resolveUrl(uriMatch[1], baseUrl) : null,
        iv: ivMatch ? ivMatch[1] : null,
        sequence: seq
      };
      if (method !== 'NONE') streamData.encryption = currentKey;
    }

    if (line.startsWith('#EXT-X-MAP')) {
      const uriMatch = line.match(/URI="([^"]+)"/);
      if (uriMatch) streamData.initSegment = resolveUrl(uriMatch[1], baseUrl);
    }

    // Byte range support: segments are ranges within a single file
    if (line.startsWith('#EXT-X-BYTERANGE')) {
      const match = line.match(/#EXT-X-BYTERANGE:(\d+)(?:@(\d+))?/);
      if (match) {
        const length = parseInt(match[1]);
        const offset = match[2] !== undefined ? parseInt(match[2]) : (byteRangeOffset || 0);
        byteRangeOffset = offset + length;
        // Will be associated with next segment
        streamData._nextByteRange = `${length}@${offset}`;
      }
    }

    if (line.startsWith('#EXTINF')) {
      const durMatch = line.match(/#EXTINF:([^,\r\n]+)/);
      const duration = durMatch ? parseFloat(durMatch[1]) : 0;

      // Also handle inline byte range on same EXTINF line: #EXTINF:10.0,\n#EXT-X-BYTERANGE...
      let nextLine = null;
      let inlineByteRange = null;

      for (let j = i + 1; j < lines.length; j++) {
        const c = lines[j].trim();
        if (!c) continue;
        if (c.startsWith('#EXT-X-BYTERANGE')) {
          const match = c.match(/#EXT-X-BYTERANGE:(\d+)(?:@(\d+))?/);
          if (match) {
            const length = parseInt(match[1]);
            const offset = match[2] !== undefined ? parseInt(match[2]) : (byteRangeOffset || 0);
            byteRangeOffset = offset + length;
            inlineByteRange = { length, offset };
          }
          continue;
        }
        if (!c.startsWith('#')) { nextLine = c; break; }
      }

      if (nextLine) {
        const seg = {
          url: resolveUrl(nextLine, baseUrl),
          duration,
          key: currentKey?.method !== 'NONE' ? currentKey : null,
          sequence: seq++
        };
        if (inlineByteRange || streamData._nextByteRange) {
          seg.byteRange = inlineByteRange || parseByteRange(streamData._nextByteRange);
          delete streamData._nextByteRange;
        }
        streamData.segments.push(seg);
        streamData.totalDuration = (streamData.totalDuration || 0) + duration;
      }
    }
  }
  streamData.segmentCount = streamData.segments.length;
}

function parseByteRange(str) {
  if (!str) return null;
  const [length, offset] = str.split('@').map(Number);
  return { length, offset };
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function isChildOfKnownMaster(url) {
  for (const stream of streamStore.values()) {
    if (!stream.isMaster) continue;
    for (const variant of stream.variants) {
      if (normalizeUrl(variant.url) === normalizeUrl(url)) return true;
    }
  }
  return false;
}

function buildFetchHeaders(details) {
  const headers = {};
  // Pass Referer so servers don't 403 hotlink-protected streams
  if (details.tabId && details.tabId > 0) {
    try {
      // We can't easily get the tab URL here synchronously,
      // but the initiator gives us origin info
      if (details.initiator) {
        headers['Origin'] = details.initiator;
        headers['Referer'] = details.initiator + '/';
      }
    } catch {}
  }
  return headers;
}

function resolveUrl(url, baseUrl) {
  if (!url) return url;
  url = url.trim();
  if (url.startsWith('http://') || url.startsWith('https://')) return url;
  try {
    if (url.startsWith('//')) return 'https:' + url;
    const base = new URL(baseUrl);
    if (url.startsWith('/')) return `${base.protocol}//${base.host}${url}`;
    return baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1) + url;
  } catch {
    return url;
  }
}

function normalizeUrl(url) {
  try {
    const u = new URL(url);
    u.search = '';
    return u.toString().toLowerCase();
  } catch {
    return url.toLowerCase();
  }
}

async function getPageTitle(tabId) {
  try {
    const tab = await chrome.tabs.get(tabId);
    return tab.title || 'Unknown Video';
  } catch {
    return 'Unknown Video';
  }
}

function storeStream(streamData) {
  streamStore.set(streamData.id, streamData);
  chrome.runtime.sendMessage({
    action: 'streamDetected',
    stream: sanitizeForMessage(streamData)
  }).catch(() => {});
}

function sanitizeForMessage(stream) {
  const { playlistText, segments, fetchHeaders, ...rest } = stream;
  return rest;
}

// ── Message handlers ──────────────────────────────────────────────────────────

chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  if (request.action === 'getStreams') {
    const streams = Array.from(streamStore.values()).map(sanitizeForMessage);
    sendResponse({ streams });
    return true;
  }

  if (request.action === 'getStream') {
    sendResponse({ stream: streamStore.get(request.streamId) || null });
    return true;
  }

  if (request.action === 'videoDetected') {
    const { url, type, tabId } = request;
    const details = { url, tabId: sender.tab?.id || tabId || -1, initiator: request.initiator };
    if (isM3U8Url(url.toLowerCase())) captureM3U8(details);
    else if (isMpdUrl(url.toLowerCase())) captureMPD(details);
    else if (isDirectVideoUrl(url.toLowerCase())) captureDirectVideo(details);
    return true;
  }

  if (request.action === 'clearStreams') {
    streamStore.clear();
    seenUrls.clear();
    sendResponse({ ok: true });
    return true;
  }
});