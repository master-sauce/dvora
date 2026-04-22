// Content script — runs at document_start in all frames
(function () {
  'use strict';

  const VIDEO_EXTS = /\.(m3u8|mpd|mp4|webm|mkv|ts|m4s|m4v|mov|avi|flv)($|\?|#)/i;
  const M3U8_RE = /\.m3u8($|\?|#)/i;
  const MPD_RE = /\.mpd($|\?|#)/i;
  const M3U8_PATH_RE = /(playlist|manifest|index|master|video|hls|stream)(\.php|\.aspx|\.m3u8)?(\?|$|\/)/i;

  function looksLikeVideo(url) {
    if (!url || typeof url !== 'string') return false;
    return VIDEO_EXTS.test(url) || M3U8_PATH_RE.test(url);
  }

  // ── Inject page-level interceptors ─────────────────────────────────────────
  // Must run in page context (not extension context) to intercept native APIs

  const script = document.createElement('script');
  script.textContent = `(function() {
    'use strict';

    const VIDEO_RE = /\\.(m3u8|mpd|mp4|webm|ts|m4s|m4v|mkv|mov)($|\\?|#)/i;
    const M3U8_PATH = /(playlist|manifest|index|master|video|hls|stream)(\\?|$|\\/)/i;

    function isVideoUrl(url) {
      if (!url || typeof url !== 'string') return false;
      return VIDEO_RE.test(url) || M3U8_PATH.test(url);
    }

    function report(url, method) {
      if (!url) return;
      window.postMessage({ type: '__HLS_DETECTED__', url: url.toString(), method }, '*');
    }

    // ── fetch ──
    const origFetch = window.fetch;
    window.fetch = function(...args) {
      try {
        const url = typeof args[0] === 'string' ? args[0] : args[0]?.url ?? '';
        if (isVideoUrl(url)) report(url, 'fetch');
      } catch {}
      return origFetch.apply(this, args);
    };

    // ── XMLHttpRequest ──
    const origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url) {
      try {
        if (isVideoUrl(url)) report(url, 'xhr');
      } catch {}
      return origOpen.apply(this, arguments);
    };

    // ── Intercept video/audio src assignments ──
    ['src', 'currentSrc'].forEach(prop => {
      ['HTMLVideoElement', 'HTMLAudioElement', 'HTMLSourceElement'].forEach(cls => {
        try {
          const proto = window[cls]?.prototype;
          if (!proto) return;
          const orig = Object.getOwnPropertyDescriptor(proto, prop);
          if (!orig || !orig.set) return;
          Object.defineProperty(proto, prop, {
            set(val) {
              try { if (val && isVideoUrl(val)) report(val, 'src-set'); } catch {}
              return orig.set.call(this, val);
            },
            get: orig.get,
            configurable: true
          });
        } catch {}
      });
    });

    // ── Intercept MediaSource / SourceBuffer ──
    // Detects blob URL streams; can't get the actual URL but signals presence
    if (window.MediaSource) {
      const origAddSB = MediaSource.prototype.addSourceBuffer;
      MediaSource.prototype.addSourceBuffer = function(mime) {
        try {
          if (mime && (mime.includes('video') || mime.includes('audio'))) {
            window.postMessage({ type: '__HLS_MEDIASOURCE__', mime }, '*');
          }
        } catch {}
        return origAddSB.apply(this, arguments);
      };
    }

    // ── Intercept createElement('script') src for players that load dynamically ──
    const origCreateElement = document.createElement.bind(document);
    document.createElement = function(tag, ...rest) {
      const el = origCreateElement(tag, ...rest);
      if (tag === 'script') {
        const origSrcDesc = Object.getOwnPropertyDescriptor(HTMLScriptElement.prototype, 'src');
        // Don't override — too risky. Just monitor attribute sets.
      }
      return el;
    };

    // ── Intercept Shaka / hls.js / video.js manifest loads via window events ──
    // Many players fire custom events we can piggyback on
    window.addEventListener('loadedmetadata', (e) => {
      try {
        const v = e.target;
        if ((v.tagName === 'VIDEO' || v.tagName === 'AUDIO') && v.currentSrc) {
          if (isVideoUrl(v.currentSrc)) report(v.currentSrc, 'loadedmetadata');
        }
      } catch {}
    }, true);

    // ── Watch for dynamically added <source> tags ──
    const srcObs = new MutationObserver((muts) => {
      for (const mut of muts) {
        for (const node of mut.addedNodes) {
          try {
            if (node.tagName === 'SOURCE' && node.src && isVideoUrl(node.src)) {
              report(node.src, 'source-tag');
            }
            // Also check children
            if (node.querySelectorAll) {
              node.querySelectorAll('source[src],video[src]').forEach(el => {
                if (el.src && isVideoUrl(el.src)) report(el.src, 'dom-scan');
              });
            }
          } catch {}
        }
      }
    });
    srcObs.observe(document.documentElement, { childList: true, subtree: true });

  })();`;

  try {
    (document.head || document.documentElement).insertBefore(script, null);
    script.remove();
  } catch (e) {
    console.warn('HLS Downloader: failed to inject script', e);
  }

  // ── Relay messages from injected script ────────────────────────────────────

  window.addEventListener('message', (event) => {
    if (event.source !== window) return;

    if (event.data?.type === '__HLS_DETECTED__') {
      const url = event.data.url;
      if (looksLikeVideo(url)) {
        chrome.runtime.sendMessage({
          action: 'videoDetected',
          url,
          method: event.data.method,
          initiator: window.location.origin
        }).catch(() => {});
      }
    }

    if (event.data?.type === '__HLS_MEDIASOURCE__') {
      // MediaSource blob stream detected — log but can't capture URL
      console.log('[HLS Downloader] MediaSource stream detected, mime:', event.data.mime);
    }
  });

  // ── Scan existing DOM ──────────────────────────────────────────────────────

  function scanDOM() {
    document.querySelectorAll('video, audio, source').forEach(el => {
      const src = el.currentSrc || el.src || el.getAttribute('src');
      if (src && looksLikeVideo(src)) {
        chrome.runtime.sendMessage({
          action: 'videoDetected',
          url: src,
          method: 'dom-scan',
          initiator: window.location.origin
        }).catch(() => {});
      }
    });

    // Also scan data-src, data-video-src etc (lazy loaders)
    document.querySelectorAll('[data-src],[data-video-src],[data-hls-src],[data-stream]').forEach(el => {
      ['data-src', 'data-video-src', 'data-hls-src', 'data-stream'].forEach(attr => {
        const val = el.getAttribute(attr);
        if (val && looksLikeVideo(val)) {
          chrome.runtime.sendMessage({
            action: 'videoDetected', url: val, method: 'data-attr',
            initiator: window.location.origin
          }).catch(() => {});
        }
      });
    });
  }

  document.addEventListener('DOMContentLoaded', scanDOM, { once: true });
  // Also scan after a delay for SPAs
  setTimeout(scanDOM, 2000);
  setTimeout(scanDOM, 5000);

  // ── Watch video element src changes ───────────────────────────────────────

  const videoObs = new MutationObserver((muts) => {
    for (const mut of muts) {
      if (mut.type === 'attributes' && (mut.attributeName === 'src' || mut.attributeName === 'data-src')) {
        const el = mut.target;
        const src = el.getAttribute('src') || el.getAttribute('data-src');
        if (src && looksLikeVideo(src)) {
          chrome.runtime.sendMessage({
            action: 'videoDetected', url: src, method: 'attr-mutation',
            initiator: window.location.origin
          }).catch(() => {});
        }
      }
      for (const node of mut.addedNodes) {
        if (!node.querySelectorAll) continue;
        node.querySelectorAll('video[src],source[src]').forEach(el => {
          const src = el.getAttribute('src');
          if (src && looksLikeVideo(src)) {
            chrome.runtime.sendMessage({
              action: 'videoDetected', url: src, method: 'node-added',
              initiator: window.location.origin
            }).catch(() => {});
          }
        });
      }
    }
  });

  videoObs.observe(document.documentElement, {
    childList: true, subtree: true, attributes: true,
    attributeFilter: ['src', 'data-src', 'data-video-src']
  });

  // ── Handle popup requests ──────────────────────────────────────────────────

  chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
    if (request.action === 'getPageInfo') {
      sendResponse({
        title: document.title,
        url: window.location.href,
        hasVideo: !!document.querySelector('video'),
        videos: Array.from(document.querySelectorAll('video')).map(v => ({
          src: v.currentSrc || v.src,
          paused: v.paused,
          duration: v.duration,
          readyState: v.readyState
        }))
      });
    }
  });

})();