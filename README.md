# DVORA — Browser UI



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
