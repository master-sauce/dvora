// Store for captured streams — keyed by normalized URL to prevent duplicates
const streamStore = new Map();
const seenUrls = new Set();

console.log('Background script started');

chrome.webRequest.onBeforeRequest.addListener(
  (details) => {
    const url = details.url.toLowerCase();
    if (url.includes('.m3u8') || url.includes('m3u8')) {
      captureM3U8(details);
    }
  },
  {
    urls: ['<all_urls>'],
    types: ['main_frame', 'sub_frame', 'stylesheet', 'script', 'image', 'object', 'xmlhttprequest', 'other', 'media']
  }
);

async function captureM3U8(details) {
  const normalizedUrl = normalizeUrl(details.url);
  if (seenUrls.has(normalizedUrl)) return;
  seenUrls.add(normalizedUrl);

  console.log('Processing M3U8:', details.url);

  try {
    const response = await fetch(details.url);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const text = await response.text();

    console.log('M3U8 first 300 chars:', text.substring(0, 300));

    if (!text.includes('#EXTM3U')) {
      console.log('Not a valid M3U8:', details.url);
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
      playlistText: text,  // Keep raw text for re-parsing at download time
      segments: [],
      variants: [],
      segmentCount: 0,
      totalDuration: 0,
      encryption: null
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

    console.log(`Stored stream: ${streamData.id} (${isMaster ? streamData.variants.length + ' variants' : streamData.segments.length + ' segments'})`);

    streamStore.set(streamData.id, streamData);

    chrome.runtime.sendMessage({
      action: 'streamDetected',
      stream: sanitizeForMessage(streamData)
    }).catch(() => {});

  } catch (error) {
    console.error('Failed to capture M3U8:', error);
    seenUrls.delete(normalizedUrl);
  }
}

function isChildOfKnownMaster(url) {
  for (const stream of streamStore.values()) {
    if (!stream.isMaster) continue;
    for (const variant of stream.variants) {
      if (normalizeUrl(variant.url) === normalizeUrl(url)) return true;
    }
  }
  return false;
}

function parseMasterPlaylist(text, baseUrl, streamData) {
  const lines = text.split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (line.startsWith('#EXT-X-STREAM-INF')) {
      const bandwidthMatch = line.match(/BANDWIDTH=(\d+)/);
      const resolutionMatch = line.match(/RESOLUTION=(\d+x\d+)/);
      const codecsMatch = line.match(/CODECS="([^"]+)"/);
      const nameMatch = line.match(/NAME="([^"]+)"/);
      const nextLine = lines[i + 1]?.trim();
      if (nextLine && !nextLine.startsWith('#')) {
        streamData.variants.push({
          url: resolveUrl(nextLine, baseUrl),
          bandwidth: bandwidthMatch ? parseInt(bandwidthMatch[1]) : null,
          resolution: resolutionMatch ? resolutionMatch[1] : null,
          codecs: codecsMatch ? codecsMatch[1] : null,
          name: nameMatch ? nameMatch[1] : null
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

  const seqMatch = text.match(/#EXT-X-MEDIA-SEQUENCE:(\d+)/);
  if (seqMatch) seq = parseInt(seqMatch[1]);

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();

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
      if (currentKey.method !== 'NONE') streamData.encryption = currentKey;
    }

    if (line.startsWith('#EXTINF')) {
      const durMatch = line.match(/#EXTINF:([^,\r\n]+)/);
      const duration = durMatch ? parseFloat(durMatch[1]) : 0;
      const nextLine = lines[i + 1]?.trim();
      if (nextLine && !nextLine.startsWith('#')) {
        streamData.segments.push({
          url: resolveUrl(nextLine, baseUrl),
          duration,
          key: currentKey,
          sequence: seq++
        });
        streamData.totalDuration += duration;
      }
    }
  }
  streamData.segmentCount = streamData.segments.length;
}

function resolveUrl(url, baseUrl) {
  if (!url) return url;
  if (url.startsWith('http://') || url.startsWith('https://')) return url;
  try {
    const base = new URL(baseUrl);
    if (url.startsWith('/')) return `${base.protocol}//${base.host}${url}`;
    const basePath = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1);
    return basePath + url;
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

function sanitizeForMessage(stream) {
  const { playlistText, segments, ...rest } = stream;
  return rest;
}

chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  if (request.action === 'getStreams') {
    const streams = Array.from(streamStore.values()).map(sanitizeForMessage);
    sendResponse({ streams });
    return true;
  }

  if (request.action === 'getStream') {
    // Return FULL data including playlistText and segments
    const stream = streamStore.get(request.streamId);
    sendResponse({ stream: stream || null });
    return true;
  }

  if (request.action === 'videoDetected') {
    const url = request.url;
    if (url.includes('.m3u8') || url.includes('m3u8')) {
      captureM3U8({ url, tabId: sender.tab?.id || -1 });
    }
    return true;
  }

  if (request.action === 'clearStreams') {
    streamStore.clear();
    seenUrls.clear();
    chrome.storage.local.clear();
    sendResponse({ ok: true });
    return true;
  }
}); 