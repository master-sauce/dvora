// Content script — runs at document_start in page context

(function () {
  'use strict';

  // Inject into page to intercept native fetch/XHR before any page code runs
  const script = document.createElement('script');
  script.textContent = `
    (function() {
      const VIDEO_RE = /\\.m3u8($|\\?|#)/i;

      // Intercept fetch
      const origFetch = window.fetch;
      window.fetch = function(...args) {
        const url = typeof args[0] === 'string' ? args[0] : (args[0]?.url ?? '');
        if (VIDEO_RE.test(url)) {
          window.postMessage({ type: '__HLS_DETECTED__', url, method: 'fetch' }, '*');
        }
        return origFetch.apply(this, args);
      };

      // Intercept XHR
      const origOpen = window.XMLHttpRequest.prototype.open;
      window.XMLHttpRequest.prototype.open = function(method, url) {
        if (url && VIDEO_RE.test(url)) {
          window.postMessage({ type: '__HLS_DETECTED__', url, method: 'xhr' }, '*');
        }
        return origOpen.apply(this, arguments);
      };

      // Intercept MediaSource for blob URL streams
      const origAddSourceBuffer = window.MediaSource?.prototype?.addSourceBuffer;
      if (origAddSourceBuffer) {
        window.MediaSource.prototype.addSourceBuffer = function(mime) {
          if (mime && mime.includes('video')) {
            window.postMessage({ type: '__HLS_MEDIASOURCE__', mime }, '*');
          }
          return origAddSourceBuffer.apply(this, arguments);
        };
      }
    })();
  `;
  (document.head || document.documentElement).insertBefore(script, null);
  script.remove();

  // Relay intercepted messages to background
  window.addEventListener('message', (event) => {
    if (event.source !== window) return;

    if (event.data?.type === '__HLS_DETECTED__') {
      chrome.runtime.sendMessage({
        action: 'videoDetected',
        url: event.data.url,
        method: event.data.method
      }).catch(() => {});
    }
  });

  // Watch for <video> elements and report their src
  const reportVideoSrc = (src) => {
    if (!src || src.startsWith('blob:') || src.startsWith('data:')) return;
    if (/\.m3u8($|\?|#)/i.test(src)) {
      chrome.runtime.sendMessage({ action: 'videoDetected', url: src, method: 'video-element' }).catch(() => {});
    }
  };

  const observer = new MutationObserver((mutations) => {
    for (const mutation of mutations) {
      for (const node of mutation.addedNodes) {
        if (node.nodeName === 'VIDEO') {
          reportVideoSrc(node.src || node.currentSrc);
          node.addEventListener('loadstart', () => reportVideoSrc(node.currentSrc), { once: true });
        }
      }
    }
  });

  observer.observe(document.documentElement, { childList: true, subtree: true });

  // Scan existing videos on load
  document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('video').forEach(v => {
      reportVideoSrc(v.currentSrc || v.src);
    });
  }, { once: true });

  // Handle getPageInfo message
  chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
    if (request.action === 'getPageInfo') {
      sendResponse({
        title: document.title,
        url: window.location.href,
        hasVideo: !!document.querySelector('video'),
        videos: Array.from(document.querySelectorAll('video')).map(v => ({
          src: v.currentSrc || v.src,
          paused: v.paused,
          duration: v.duration
        }))
      });
    }
  });
})();
