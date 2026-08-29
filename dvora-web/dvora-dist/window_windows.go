//go:build windows

package main

import (
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"

	webview "github.com/webview/webview_go"
)

var (
	bravePath     string
	bravePathOnce sync.Once
)

// findBrave locates brave.exe once; returns "" if not installed.
func findBrave() string {
	bravePathOnce.Do(func() {
		candidates := []string{
			filepath.Join(os.Getenv("LOCALAPPDATA"), `BraveSoftware\Brave-Browser\Application\brave.exe`),
			`C:\Program Files\BraveSoftware\Brave-Browser\Application\brave.exe`,
			`C:\Program Files (x86)\BraveSoftware\Brave-Browser\Application\brave.exe`,
		}
		for _, p := range candidates {
			if _, err := os.Stat(p); err == nil {
				bravePath = p
				log.Printf("Brave detected at %s — external links will open there", p)
				return
			}
		}
		log.Printf("Brave not found — external links will open in default browser")
	})
	return bravePath
}

// openExternal opens a link in Brave if installed, else Windows default browser.
func openExternal(link string) {
	if !strings.HasPrefix(link, "http://") && !strings.HasPrefix(link, "https://") {
		return
	}
	if brave := findBrave(); brave != "" {
		log.Printf("Opening in Brave: %s", link)
		if err := exec.Command(brave, link).Start(); err == nil {
			return
		}
	}
	log.Printf("Opening in default browser: %s", link)
	openBrowser(link)
}

// openUI opens the built-in webview window on Windows.
// External links are intercepted and routed to Brave (if installed) or the
// default browser, instead of WebView2 spawning bare Edge windows.
func openUI(url string) {
	w := webview.New(false)
	defer w.Destroy()
	w.SetTitle("Dvora")
	w.SetSize(1280, 800, webview.HintNone)

	// Go function callable from JS as window.openExternalDvora(url)
	if err := w.Bind("openExternalDvora", openExternal); err != nil {
		log.Printf("Bind openExternalDvora failed: %v", err)
	}

	// Intercept window.open and target=_blank clicks; route to openExternal.
	w.Init(`
(function() {
  const origOpen = window.open;
  window.open = function(url, target, features) {
    if (url && /^https?:/i.test(url)) {
      window.openExternalDvora(url);
      return null;
    }
    return origOpen.call(window, url, target, features);
  };
  document.addEventListener('click', function(e) {
    const a = e.target.closest && e.target.closest('a[target="_blank"]');
    if (a && a.href && /^https?:/i.test(a.href)) {
      e.preventDefault();
      e.stopPropagation();
      window.openExternalDvora(a.href);
    }
  }, true);
})();
`)

	w.Navigate(url)
	w.Run()
}
