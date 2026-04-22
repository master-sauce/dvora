# DVORA — Browser UI

## for android app

download and install the apk via the releases tag on your android phone.

## for Web


```
git clone https://github.com/master-sauce/dvora.git
cd dvora/dvora-web/dvora-dist
```

## Run
```bash
# Windows
start_windows.bat

# Mac / Linux
start_linux_mac.sh

# Then open
http://localhost:8080
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
Queries [Wizdom.xyz](https://wizdom.xyz) for Hebrew subtitles in order to upload your own.

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


## Legal Disclaimer & Fair Use
DVORA is a technical tool designed for indexing and aggregation purposes only.

Aggregator & Indexer: This application functions solely as a search interface that aggregates and indexes publicly available information from third-party websites.

No Content Hosting: DVORA does not host, store, or distribute any media files, videos, or copyrighted content on its servers.

Third-Party Relations: The developer of this project has no affiliation, partnership, or association with the third-party websites or API providers indexed by the tool.

User Responsibility: Users are responsible for ensuring that their use of this tool complies with local laws and the terms of service of the original content providers.

Educational Purpose: This project was developed as a technical proof-of-concept for web scraping, API integration, and UI development.