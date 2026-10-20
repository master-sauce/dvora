# DVORA — show/movie indexer

Search engine for shows & movies — an Android app and a self-hosted web version.

---

## Android app

Download and install the apk from the releases tag on your Android phone.

### Search
- **Auto-scan sources** — fetches each configured search page and matches links against a regex built from your query.
- **API sites** — `v1:` JSON endpoints and `stremio:` (Cinemeta) catalogs, with per-source log output. **You can add your own API endpoints** to the list (one per line, `v1:` or `stremio:` prefix, bare URLs default to `v1:`), with custom landing URLs and match keys.
- **Manual checks** — links to verify yourself in the browser.
- **IMDb suggestions** while typing, plus poster/year/rating cards from IMDb results.
- **Bookmarks** with reminders (one-time or recurring), season/episode tracking, playback timestamp and free-text notes; import/export as JSON.
- **Next-episode lookup** — a bookmark's reminder sheet can ask TVmaze when the show's next episode airs and offer to set (or overwrite) a reminder for that exact date — no manual date hunting.

### Hebrew subtitles
- **Wizdom.xyz** search with rich cards (poster, year, rating, genres, Hebrew title).
- **OpenSubtitles** — one-tap pre-searched Hebrew results in the in-app browser (the site sits behind a bot-wall only a real browser passes).

### Built-in privacy browser
- **Multi-tab browsing** — a vertical tab list behind a live tab-count button in the top bar (open/switch/close, add normal or incognito tabs). Regular tabs live for the whole app session; incognito tabs die with the browser screen and are never saved.
- **Incognito** — no cookies, DOM storage, database or cache; the app-wide cookie jar is closed while any incognito tab exists.
- **History** — every committed page URL (including SPA route changes) is recorded for normal tabs and persisted; incognito pages are never recorded. Searchable list with one-tap reopen, and a clear action.
- **Per-site ad-block shield** — one tap disables/enables the blocker for the current site (everything the page loads is allowed, requests stay visible in the inspector).
- **Ad & tracker blocker** — 21 bundled, auto-updating filter lists (EasyList, EasyPrivacy, EasyList Cookie, EasyList Hebrew, uBlock Origin ×4, AdGuard ×6, Fanboy ×2, anti-adblock ×3, Peter Lowe hosts, oisd big). All lists share a 7-day update cadence, checksum + size validation keeps the last good copy on failure, and each list has its own toggle and live status.
- **Request inspector** — every request of the current page (page / js / css / img / frame / api / media), with the blocking rule shown for blocked ones, live filters, search, and long-press download.
- **Media capture + downloads** — the sniffer lists playlists, masters, segments and direct files with size, duration, quality and filename; downloads run through the **yt-dlp engine** (up to 4 concurrent) with progress notifications, per-job cancel and automatic temp-file cleanup. The highest offered quality is what gets downloaded.
- **Subtitle downloads** — subtitle files are fetched through the same engine into the dvora folder (default `Download/dvora`, configurable).
- **Clicked download links** — media links start a download and open the live downloads screen; ordinary files (apk/zip/pdf…) go to the system DownloadManager with their original names.
- **Live URL bar** — follows every committed URL, including redirects and single-page-app route changes; file-upload picker, SSL warning dialog, cross-site confirmation, share sheet.

### Settings
- Sources editor (Shows / Movies / Manual checks / API sites / Exclusions), link handling (external chain or in-app browser), filter-list toggles with per-list status and refresh, language, and "clear browsing data" (cookies, storage, history).

---

## Web version

```
git clone https://github.com/master-sauce/dvora.git
cd dvora/dvora-web/dvora-dist
```

## Run

Prebuilt binaries ship in `dvora-web/dvora-dist/` — no scripts needed:

- **Windows** — double-click `dvora.exe`. DVORA opens in its own native window (uses the built-in WebView2, no browser required).
- **macOS** — `chmod +x dvora_mac && ./dvora_mac`, then open `http://localhost:8080` if the browser didn't open. First run: right-click the file → Open → Open.
- **Linux** — `chmod +x dvora_linux && ./dvora_linux`, then open `http://localhost:8080` if the browser didn't open.

> Mac/Linux builds serve the UI locally and open your default browser — keep the terminal window open; Ctrl+C quits.

Build from source (Go 1.22+):

```bash
# Windows (native window, requires CGO for the webview)
go build -ldflags "-H windowsgui" -o dvora.exe .

# Linux (browser-based, no CGO)
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o dvora_linux .

# Mac (browser-based, no CGO)
CGO_ENABLED=0 GOOS=darwin GOARCH=amd64 go build -o dvora_mac .
```

## Modes
Choose **Shows** or **Movies** before scanning — this affects which URL list is used and which API endpoint is called.

## Search Types

### Auto Scan
Fetches the search page and scans HTML links for matches using a regex built from your query.
Configured via **Shows URLs** / **Movies URLs** in Settings.

### API Sites
Calls a JSON search API and matches titles against your query. Returns up to 10 matches with names and links.
Two API types are supported — set via prefix in the **API Sites** config:

| Prefix | API style | Example entry |
|---|---|---|
| `v1:` | `/searching?q=` endpoint | `v1:https://ww4.fmovies.co` |
| `stremio:` | Cinemeta catalog endpoint | `stremio:https://v3-cinemeta.strem.io` |

A bare URL with no prefix is treated as `v1:`.

### Manual Checks
URLs that are NOT auto-scanned — shown as clickable links for you to verify in your browser.
Configured via **Manual Checks** in Settings.

### Search Subtitles
Queries [Wizdom.xyz](https://wizdom.xyz) for Hebrew subtitles in order to upload your own, and offers the same one-tap **OpenSubtitles** Hebrew search card.

Shows poster, year, rating, genres, and a direct link to the subtitle page.

## Site File Format
One URL prefix per line. The search term is appended directly.

- `+url` → spaces in query become `+`
- `-url` → spaces become `-`
- plain url → spaces kept as-is
- Lines starting with `#` are comments

## Settings Panel
All config files can be edited live from the **Settings** panel (⚙ top right) without restarting the server. Tabs:
- **Shows URLs** — auto-scan targets for show searches
- **Movies URLs** — auto-scan targets for movie searches
- **Manual Checks** — links shown for manual verification
- **API Sites** — JSON API endpoints (`v1:` or `stremio:` prefix)
- **Search Logs** — verbose per-site logs from the last scan and last subtitle search


## License

MIT License

Copyright (c) 2026 master-sauce

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.




## Legal Disclaimer & Fair Use
DVORA is a technical tool designed for indexing and aggregation purposes only.

Aggregator & Indexer: This application functions solely as a search interface that aggregates and indexes publicly available information from third-party websites.

Downloads — Content Warning: The built-in downloader (yt-dlp engine) is provided as a technical capability and transfer mechanism only. Only download content that you own, that is offered under a license permitting download, or that you are otherwise legally entitled to obtain. Do not use it to download copyrighted material you do not own or have permission to download, and respect the terms of service of every website you visit. You are solely responsible for how you use this feature and for any content you obtain with it.

No Content Hosting: DVORA does not host, store, or distribute any media files, videos, or copyrighted content on its servers.

Third-Party Relations: The developer of this project has no affiliation, partnership, or association with the third-party websites or API providers indexed by the tool.

User Responsibility: Users are responsible for ensuring that their use of this tool complies with local laws and the terms of service of the original content providers.

Educational Purpose: This project was developed as a technical proof-of-concept for web scraping, API integration, and UI development.
