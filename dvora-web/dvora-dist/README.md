# DVORA — Find Where to Watch Anything

DVORA checks lots of streaming sites at once and shows you which ones have the movie or show you want. Just type and search.

> **⚠️ Install an ad blocker before streaming.** Free streaming sites are ad-heavy and some ads are malicious. Use [uBlock Origin](https://ublockorigin.com) (Chrome/Firefox/Edge) or use [Brave](https://brave.com), which blocks ads out of the box. On Windows, if Brave is installed, DVORA automatically opens streaming links in Brave.

---

## Getting Started (the easy way)

### 🪟 Windows
Double-click **`dvora.exe`**. That's it. DVORA opens in its own window — no browser needed.

When you click a streaming-site result, it opens in your browser:
- **Brave installed?** → Links open in Brave automatically (its built-in ad blocker keeps streams clean).
- **No Brave?** → Links open in your Windows default browser. Install an ad blocker there first.

### 🍎 Mac
Double-click **`dvora_mac`** (or run `./dvora_mac` from Terminal). Your default browser opens DVORA automatically.

If macOS says the app is "from an unidentified developer": right-click the file → **Open** → **Open** again. You only need to do this once.

### 🐧 Linux
Run:
```bash
chmod +x dvora_linux
./dvora_linux
```
Your default browser opens DVORA automatically.

> On Mac/Linux, keep the terminal window open while using DVORA. Close it (or press Ctrl+C) to quit.

---

## How to Use

1. Pick **Shows** or **Movies** at the top.
2. Type a title and hit search.
3. DVORA scans every site in its lists and shows you which ones have it. Click a result to visit the site.

**That's the whole thing.** Everything below is optional.

---

## The Four Search Types (explained simply)

| Type | What it does |
|---|---|
| **Auto Scan** | Visits each site's search page and looks for your title in the results. |
| **API Sites** | Asks sites with a built-in search "API" directly — faster and more accurate. |
| **Manual Checks** | Sites DVORA can't read automatically. Shown as buttons so you can check them yourself with one click. |
| **Subtitles** | Searches Wizdom.xyz for Hebrew subtitles, with poster, year, and rating. |

## Settings (the ⚙ button, top right)

Everything is editable while DVORA is running — no restart needed:

- **Shows URLs / Movies URLs** — the sites Auto Scan checks.
- **Manual Checks** — the sites shown as clickable buttons.
- **API Sites** — sites with direct-search APIs. Two flavors:
  - `v1:https://example.com` — standard search endpoint
  - `stremio:https://v3-cinemeta.strem.io` — Stremio-style catalog
- **Search Logs** — behind-the-scenes detail from your last search, useful when a site stops working.

### URL list formatting tricks
One site per line. A symbol at the start controls how spaces in your title are handled:

- `+https://site.com/search?q=` → spaces become `+`
- `-https://site.com/search?q=` → spaces become `-`
- `https://site.com/search?q=` → spaces kept as-is
- Lines starting with `#` are ignored (use them for notes)

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Window won't open (Windows) | Make sure Windows is up to date — DVORA uses the built-in WebView2 component. |
| Browser didn't open (Mac/Linux) | Open http://localhost:8080 yourself. |
| Mac says "unidentified developer" | Right-click the file → Open → Open. One-time only. |
| Nothing happens at all | Run from a terminal — it prints errors to the console. |
| Port already in use | Another copy of DVORA is already running. Close it first. |

---

## For Developers: Building From Source

You need [Go](https://go.dev) 1.22+.

```bash
# Windows (native window, requires CGO for the webview)
go build -ldflags "-H windowsgui" -o dvora.exe .

# Linux (browser-based, no extra dependencies)
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o dvora_linux .

# Mac (browser-based, no extra dependencies)
CGO_ENABLED=0 GOOS=darwin GOARCH=amd64 go build -o dvora_mac .
```

All three can be built from any one OS — no cross-compilation toolchains needed. Only the Windows build uses CGO (for its native window); Mac and Linux builds skip the webview and open the user's browser instead.
