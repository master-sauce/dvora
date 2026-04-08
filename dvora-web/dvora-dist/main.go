package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"
)

var userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

// ─── Types ────────────────────────────────────────────────────────────────────

type LogEntry struct {
	Level   string `json:"level"`
	Message string `json:"message"`
}

type MovieMatch struct {
	Name string `json:"name"`
	URL  string `json:"url"`
}

type SiteResult struct {
	URL      string       `json:"url"`
	Found    bool         `json:"found"`
	Error    string       `json:"error,omitempty"`
	Details  string       `json:"details,omitempty"`
	Type     string       `json:"type"`
	MovieURL string       `json:"movie_url,omitempty"`
	Matches  []MovieMatch `json:"matches,omitempty"`
	Logs     []LogEntry   `json:"logs"`
}

type SearchResponse struct {
	Query   string       `json:"query"`
	Results []SiteResult `json:"results"`
}

// IMDb suggestion API
type ImdbResult struct {
	ImdbID    string `json:"imdbId"`
	Title     string `json:"title"`
	Year      string `json:"year"`
	MediaType string `json:"mediaType"`
	PosterURL string `json:"posterUrl"`
	ImdbURL   string `json:"imdbUrl"`
}

type imdbSuggResp struct {
	D []imdbSuggItem `json:"d"`
}

type imdbSuggItem struct {
	ID string     `json:"id"`
	L  string     `json:"l"`
	Y  int        `json:"y"`
	Q  string     `json:"q"`
	I  *imdbImage `json:"i"`
}

type imdbImage struct {
	ImageURL string `json:"imageUrl"`
}

// Wizdom search API item
type wizdomItem struct {
	Title   string `json:"title"`
	TitleEn string `json:"title_en"`
	Imdb    string `json:"imdb"`
	Type    string `json:"type"`
}

// Wizdom releases API — rich metadata per title.
// Subs is RawMessage because movies return an array and TV returns a seasons object.
type WizdomRelease struct {
	Imdb        string          `json:"imdb"`
	Title       string          `json:"title"`    // Hebrew title
	TitleEn     string          `json:"title_en"` // English title
	Year        int             `json:"year"`
	Rating      string          `json:"rating"`
	Genres      string          `json:"genres"`
	PosterSmall string          `json:"poster_small"`
	Type        string          `json:"type"` // "movie" or "tv"
	Subs        json.RawMessage `json:"subs"`
}

// SubResult — returned to the frontend, includes rich Wizdom data
type SubResult struct {
	URL       string `json:"url"`
	Found     bool   `json:"found"`
	Title     string `json:"title"`
	TitleHe   string `json:"titleHe,omitempty"`
	ImdbID    string `json:"imdbId,omitempty"`
	Year      int    `json:"year,omitempty"`
	Rating    string `json:"rating,omitempty"`
	Genres    string `json:"genres,omitempty"`
	PosterURL string `json:"posterUrl,omitempty"`
	Type      string `json:"type,omitempty"`
	SubsCount int    `json:"subsCount,omitempty"`
	Details   string `json:"details,omitempty"`
	Error     string `json:"error,omitempty"`
}

type SubResponse struct {
	Query   string      `json:"query"`
	Mode    string      `json:"mode"`
	Results []SubResult `json:"results"`
}

// ─── countSubs ────────────────────────────────────────────────────────────────
// Movies → subs is an array of versions.
// TV     → subs is a seasons object: { "1": {...}, "2": {...} }.
// Returns the element/key count, or 0 if empty/unparseable.
func countSubs(raw json.RawMessage) int {
	if len(raw) == 0 {
		return 0
	}
	t := strings.TrimSpace(string(raw))
	if t == "" || t == "null" || t == "[]" || t == "{}" {
		return 0
	}
	var arr []json.RawMessage
	if json.Unmarshal(raw, &arr) == nil {
		return len(arr)
	}
	var obj map[string]json.RawMessage
	if json.Unmarshal(raw, &obj) == nil {
		return len(obj)
	}
	return 1 // non-empty but opaque structure → at least 1
}

// ─── HTML parsing ─────────────────────────────────────────────────────────────

func extractLinks(body []byte) []string {
	re := regexp.MustCompile(`(?i)href=["']([^"']+)["']`)
	ms := re.FindAllSubmatch(body, -1)
	var links []string
	for _, m := range ms {
		if len(m) > 1 {
			links = append(links, string(m[1]))
		}
	}
	return links
}

func noResultsIndicator(body []byte) string {
	lower := strings.ToLower(string(body))
	for _, ind := range []string{
		"no result found.", "no result found", "no results found", "no results",
		"nothing found", "not found", "no matches", "0 results",
		"could not find", "couldn't find", "search returned no results",
		"sorry, no results", "no items found", "your search did not match",
		"did not match any", "no search results",
	} {
		if strings.Contains(lower, ind) {
			return ind
		}
	}
	return ""
}

// ─── Site scan ────────────────────────────────────────────────────────────────

func scanSite(siteURL, searchTerm string) (bool, string, []LogEntry, error) {
	var logs []LogEntry
	add := func(lvl, msg string) { logs = append(logs, LogEntry{lvl, msg}) }

	client := &http.Client{Timeout: 10 * time.Second}
	req, err := http.NewRequest("GET", siteURL, nil)
	if err != nil {
		return false, "", logs, err
	}
	req.Header.Set("User-Agent", userAgent)
	add("info", "GET "+siteURL)

	resp, err := client.Do(req)
	if err != nil {
		return false, "", logs, err
	}
	defer resp.Body.Close()
	add("info", fmt.Sprintf("HTTP %d", resp.StatusCode))
	if resp.StatusCode != 200 {
		return false, "", logs, fmt.Errorf("HTTP %d", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)
	links := extractLinks(body)
	add("info", fmt.Sprintf("Extracted %d links", len(links)))

	words := strings.Fields(strings.ToLower(searchTerm))
	if len(words) == 0 {
		return false, "", logs, nil
	}
	var pb strings.Builder
	for i, w := range words {
		if i > 0 {
			pb.WriteString(`[\s\-\+\.\/]+`)
		}
		pb.WriteString(regexp.QuoteMeta(w))
	}
	pat := regexp.MustCompile(pb.String())
	add("info", "Regex: /"+pb.String()+"/")

	skip, _ := readLines("exclusions.txt")
	if len(skip) == 0 {
		skip = []string{"addtoany.com", "facebook.com", "twitter.com", "reddit.com",
			"pinterest.com", "whatsapp.com", "t.me", "mailto:", "/login", "/register",
			"/signup", "/feed", "#", "/filter", "/search", "/browser", "/?s="}
	}

	var matched []string
	skipped := 0
	for _, link := range links {
		ll := strings.ToLower(link)
		s := false
		for _, d := range skip {
			if strings.Contains(ll, d) {
				s = true
				break
			}
		}
		if !s && (strings.HasPrefix(ll, "/search/") || strings.HasPrefix(ll, "search/") ||
			strings.HasPrefix(ll, "/search?")) {
			s = true
		}
		if s {
			skipped++
			continue
		}
		if pat.MatchString(ll) {
			matched = append(matched, link)
		}
	}
	add("info", fmt.Sprintf("Skipped %d, matched %d", skipped, len(matched)))

	if len(matched) > 0 {
		for i, m := range matched {
			if i >= 5 {
				add("match", fmt.Sprintf("  ...and %d more", len(matched)-5))
				break
			}
			add("match", "✓ "+m)
		}
		add("verdict", fmt.Sprintf("FOUND — %d link(s) matched", len(matched)))
		return true, fmt.Sprintf("Matches found: %d", len(matched)), logs, nil
	}
	if ind := noResultsIndicator(body); ind != "" {
		add("warn", `No-results text: "`+ind+`"`)
		add("verdict", `NOT FOUND — no-results indicator: "`+ind+`"`)
		return false, "Detected: " + ind, logs, nil
	}
	add("verdict", "NOT FOUND — 0 links matched")
	return false, "No matches found", logs, nil
}

// titleMatches checks whether a result title contains the search term,
// trying space, dash, and plus as separators in both the query and the title.
func titleMatches(title, searchTerm string) bool {
	t := strings.ToLower(title)
	seps := []string{" ", "-", "+"}
	for _, qs := range seps {
		q := strings.ToLower(strings.ReplaceAll(searchTerm, " ", qs))
		// Also normalise the title with each separator before comparing
		for _, ts := range seps {
			norm := strings.ReplaceAll(t, ts, qs)
			if strings.Contains(norm, q) {
				return true
			}
		}
	}
	return false
}

// ─── Movie API (v1) ───────────────────────────────────────────────────────────

func scanMovieAPI(baseURL, searchTerm string) (bool, []MovieMatch, []LogEntry, error) {
	var logs []LogEntry
	add := func(lvl, msg string) { logs = append(logs, LogEntry{lvl, msg}) }

	client := &http.Client{Timeout: 10 * time.Second}

	// Try space, dash, and plus variants of the search term
	variants := []struct{ sep, label string }{
		{" ", "space"},
		{"-", "dash"},
		{"+", "plus"},
	}

	seenNames := make(map[string]bool)
	var allMatches []MovieMatch

	for _, v := range variants {
		q := strings.ReplaceAll(searchTerm, " ", v.sep)
		apiURL := baseURL + "/searching?q=" + q + "&limit=40&offset=0"
		add("info", fmt.Sprintf("API GET %s [%s]", apiURL, v.label))

		req, _ := http.NewRequest("GET", apiURL, nil)
		req.Header.Set("User-Agent", userAgent)
		req.Header.Set("Accept", "application/json")
		resp, err := client.Do(req)
		if err != nil {
			add("warn", fmt.Sprintf("[%s] request error: %s", v.label, err.Error()))
			continue
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		add("info", fmt.Sprintf("[%s] HTTP %d", v.label, resp.StatusCode))
		if resp.StatusCode != 200 {
			add("warn", fmt.Sprintf("[%s] skipping — HTTP %d", v.label, resp.StatusCode))
			continue
		}

		var ar struct {
			Data []struct {
				T string `json:"t"`
				Y int    `json:"y"`
			} `json:"data"`
		}
		if err := json.Unmarshal(body, &ar); err != nil {
			add("warn", fmt.Sprintf("[%s] JSON parse error: %s", v.label, err.Error()))
			continue
		}
		add("info", fmt.Sprintf("[%s] %d results", v.label, len(ar.Data)))

		searchURL := baseURL + "/search/?q=" + q
		for _, item := range ar.Data {
			if len(allMatches) >= 10 {
				break
			}
			key := strings.ToLower(item.T)
			if seenNames[key] {
				continue
			}
			if titleMatches(item.T, searchTerm) {
				seenNames[key] = true
				allMatches = append(allMatches, MovieMatch{Name: item.T, URL: searchURL})
				add("match", fmt.Sprintf(`✓ [%s] "%s" (%d)`, v.label, item.T, item.Y))
			} else {
				add("skip", fmt.Sprintf(`  [%s] no match: "%s"`, v.label, item.T))
			}
		}
	}

	if len(allMatches) > 0 {
		add("verdict", fmt.Sprintf("FOUND — %d result(s)", len(allMatches)))
		return true, allMatches, logs, nil
	}
	add("verdict", "NOT FOUND — 0 matched across all variants")
	return false, nil, logs, nil
}

// ─── Stremio API ──────────────────────────────────────────────────────────────

func scanStremio(baseURL, searchTerm, mode string) (bool, []MovieMatch, []LogEntry, error) {
	var logs []LogEntry
	add := func(lvl, msg string) { logs = append(logs, LogEntry{lvl, msg}) }

	mt := "series"
	if mode == "movies" {
		mt = "movie"
	}

	client := &http.Client{Timeout: 10 * time.Second}

	// Try space, dash, and plus variants of the search term
	variants := []struct{ sep, label string }{
		{" ", "space"},
		{"-", "dash"},
		{"+", "plus"},
	}

	seenNames := make(map[string]bool)
	var allMatches []MovieMatch

	for _, v := range variants {
		q := strings.ReplaceAll(searchTerm, " ", v.sep)
		apiURL := baseURL + "/catalog/" + mt + "/top/search=" + q + ".json"
		add("info", fmt.Sprintf("Stremio GET %s [%s]", apiURL, v.label))

		req, _ := http.NewRequest("GET", apiURL, nil)
		req.Header.Set("User-Agent", userAgent)
		req.Header.Set("Accept", "application/json")
		resp, err := client.Do(req)
		if err != nil {
			add("warn", fmt.Sprintf("[%s] request error: %s", v.label, err.Error()))
			continue
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		add("info", fmt.Sprintf("[%s] HTTP %d", v.label, resp.StatusCode))
		if resp.StatusCode != 200 {
			add("warn", fmt.Sprintf("[%s] skipping — HTTP %d", v.label, resp.StatusCode))
			continue
		}

		var ar struct {
			Metas []struct {
				ID          string `json:"id"`
				ImdbID      string `json:"imdb_id"`
				Type        string `json:"type"`
				Name        string `json:"name"`
				ReleaseInfo string `json:"releaseInfo"`
			} `json:"metas"`
		}
		if err := json.Unmarshal(body, &ar); err != nil {
			add("warn", fmt.Sprintf("[%s] JSON parse error: %s", v.label, err.Error()))
			continue
		}
		add("info", fmt.Sprintf("[%s] %d results", v.label, len(ar.Metas)))

		for _, item := range ar.Metas {
			if len(allMatches) >= 10 {
				break
			}
			key := strings.ToLower(item.Name)
			if seenNames[key] {
				continue
			}
			if titleMatches(item.Name, searchTerm) {
				seenNames[key] = true
				id := item.ImdbID
				if id == "" {
					id = item.ID
				}
				su := "https://web.stremio.com/#/detail/" + item.Type + "/" + id + "/" + id
				allMatches = append(allMatches, MovieMatch{Name: item.Name, URL: su})
				add("match", fmt.Sprintf(`✓ [%s] "%s" (%s)`, v.label, item.Name, item.ReleaseInfo))
			} else {
				add("skip", fmt.Sprintf(`  [%s] no match: "%s"`, v.label, item.Name))
			}
		}
	}

	if len(allMatches) > 0 {
		add("verdict", fmt.Sprintf("FOUND — %d result(s)", len(allMatches)))
		return true, allMatches, logs, nil
	}
	add("verdict", "NOT FOUND — 0 matched across all variants")
	return false, nil, logs, nil
}

// ─── IMDb suggestion API ─────────────────────────────────────────────────────

func searchIMDb(searchTerm string) ([]ImdbResult, error) {
	q := strings.ToLower(strings.TrimSpace(searchTerm))
	q = strings.ReplaceAll(q, " ", "_")

	firstChar := "a"
	for _, c := range q {
		if (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') {
			firstChar = string(c)
			break
		}
	}

	imdbURL := fmt.Sprintf("https://v3.sg.media-imdb.com/suggestion/%s/%s.json",
		firstChar, url.PathEscape(q))

	client := &http.Client{Timeout: 10 * time.Second}
	req, err := http.NewRequest("GET", imdbURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	body, _ := io.ReadAll(resp.Body)

	var parsed imdbSuggResp
	if err := json.Unmarshal(body, &parsed); err != nil {
		return nil, err
	}

	var results []ImdbResult
	for _, item := range parsed.D {
		if !strings.HasPrefix(item.ID, "tt") {
			continue
		}
		poster := ""
		if item.I != nil {
			poster = item.I.ImageURL
		}
		year := ""
		if item.Y > 0 {
			year = fmt.Sprintf("%d", item.Y)
		}
		results = append(results, ImdbResult{
			ImdbID:    item.ID,
			Title:     item.L,
			Year:      year,
			MediaType: item.Q,
			PosterURL: poster,
			ImdbURL:   "https://www.imdb.com/title/" + item.ID + "/",
		})
		if len(results) >= 10 {
			break
		}
	}
	return results, nil
}

// ─── Subtitle search ──────────────────────────────────────────────────────────
// Step 1 — collect candidate IMDb IDs from Wizdom search + IMDb suggestion API.
// Step 2 — verify each candidate against wizdom.xyz/api/releases/{id}:
//   - Uses a no-redirect client — Wizdom returns 3xx with JSON body for TV shows.
//     Following the redirect would lose the response body.
//   - 4xx / 5xx → skip (title has no data on Wizdom).
//   - Empty / null body → skip.
//   - Non-empty body → confirmed. Parse WizdomRelease for rich metadata.
//
// The URL path (tv/movie) always comes from the user's mode toggle, never from
// the release's own type field (which can be null or inconsistent).
func searchSubtitles(searchTerm, mode string) SubResponse {
	typePath := "tv"
	if mode == "movies" {
		typePath = "movie"
	}

	wizdomIds := make(map[string]string) // imdbId → display title

	// ── Source A: Wizdom search API ───────────────────────────────────────────
	wizdomAPIURL := "https://wizdom.xyz/api/search?search=" + url.QueryEscape(searchTerm) + "&page=0"
	func() {
		req, err := http.NewRequest("GET", wizdomAPIURL, nil)
		if err != nil {
			return
		}
		req.Header.Set("User-Agent", userAgent)
		c := &http.Client{Timeout: 10 * time.Second}
		resp, err := c.Do(req)
		if err != nil {
			return
		}
		defer resp.Body.Close()
		if resp.StatusCode != 200 {
			return
		}
		body, _ := io.ReadAll(resp.Body)
		var items []wizdomItem
		if json.Unmarshal(body, &items) != nil {
			return
		}
		sl := strings.ToLower(searchTerm)
		for _, item := range items {
			if item.Imdb == "" {
				continue
			}
			enM := strings.Contains(strings.ToLower(item.TitleEn), sl)
			heM := strings.Contains(strings.ToLower(item.Title), sl)
			if enM || heM {
				title := item.TitleEn
				if title == "" {
					title = item.Title
				}
				if title == "" {
					title = "Unknown"
				}
				wizdomIds[item.Imdb] = title
			}
		}
	}()

	// ── Source B: IMDb suggestion API ─────────────────────────────────────────
	if imdbResults, err := searchIMDb(searchTerm); err == nil {
		for _, r := range imdbResults {
			if _, exists := wizdomIds[r.ImdbID]; !exists {
				wizdomIds[r.ImdbID] = r.Title
			}
		}
	}

	if len(wizdomIds) == 0 {
		return SubResponse{
			Query: searchTerm, Mode: mode,
			Results: []SubResult{},
		}
	}

	// ── Step 2: verify each candidate via /api/releases/{imdbId} ─────────────
	// No-redirect client: Wizdom sends HTTP 3xx for TV with the JSON in the body.
	relClient := &http.Client{
		Timeout: 10 * time.Second,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	ch := make(chan SubResult, len(wizdomIds))
	var wg sync.WaitGroup

	for imdbID, displayTitle := range wizdomIds {
		wg.Add(1)
		go func(imdbID, displayTitle string) {
			defer wg.Done()

			relURL := "https://wizdom.xyz/api/releases/" + imdbID
			finalURL := "https://wizdom.xyz/" + typePath + "/" + imdbID

			req, err := http.NewRequest("GET", relURL, nil)
			if err != nil {
				return
			}
			req.Header.Set("User-Agent", userAgent)
			req.Header.Set("Accept", "application/json")

			resp, err := relClient.Do(req)
			if err != nil {
				return
			}
			body, _ := io.ReadAll(resp.Body)
			resp.Body.Close()

			// Skip error responses
			if resp.StatusCode >= 400 {
				return
			}

			// Skip genuinely empty responses
			trimmed := strings.TrimSpace(string(body))
			if trimmed == "" || trimmed == "null" || trimmed == "[]" || trimmed == "{}" {
				return
			}

			// Parse rich metadata — best effort, never blocks the result
			var release WizdomRelease
			json.Unmarshal(body, &release)

			title := release.TitleEn
			if title == "" {
				title = release.Title
			}
			if title == "" {
				title = displayTitle
			}

			titleHe := ""
			if release.Title != "" && release.Title != title {
				titleHe = release.Title
			}

			ch <- SubResult{
				URL:       finalURL,
				Found:     true,
				Title:     title,
				TitleHe:   titleHe,
				ImdbID:    imdbID,
				Year:      release.Year,
				Rating:    release.Rating,
				Genres:    release.Genres,
				PosterURL: release.PosterSmall,
				Type:      typePath, // always from user toggle, not from release.Type
				SubsCount: countSubs(release.Subs),
			}
		}(imdbID, displayTitle)
	}

	go func() { wg.Wait(); close(ch) }()

	var results []SubResult
	for sr := range ch {
		results = append(results, sr)
	}
	if results == nil {
		results = []SubResult{}
	}

	return SubResponse{Query: searchTerm, Mode: mode, Results: results}
}

// ─── File helpers ─────────────────────────────────────────────────────────────

func readLines(path string) ([]string, error) {
	f, err := os.Open(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	defer f.Close()
	var lines []string
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line != "" && !strings.HasPrefix(line, "//") {
			lines = append(lines, line)
		}
	}
	return lines, sc.Err()
}

func readRaw(path string) string {
	b, _ := os.ReadFile(path)
	return string(b)
}

// ─── HTTP Handlers ────────────────────────────────────────────────────────────

func searchHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("Access-Control-Allow-Origin", "*")

	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, `{"error":"streaming unsupported"}`, 500)
		return
	}

	q := r.URL.Query().Get("q")
	mode := r.URL.Query().Get("mode")

	send := func(v interface{}) {
		data, _ := json.Marshal(v)
		fmt.Fprintf(w, "data: %s\n\n", data)
		flusher.Flush()
	}

	if q == "" {
		send(map[string]string{"event": "error", "message": "missing query"})
		return
	}
	if mode != "shows" && mode != "movies" {
		send(map[string]string{"event": "error", "message": "invalid mode"})
		return
	}

	send(map[string]string{"event": "meta", "query": q})

	// ── Auto sites (parallel) ─────────────────────────────────────────────────
	autoFile := "shows.txt"
	if mode == "movies" {
		autoFile = "movies.txt"
	}
	autoLines, _ := readLines(autoFile)

	if len(autoLines) > 0 {
		send(map[string]string{"event": "section_start", "section": "auto",
			"title": "Auto Scan — " + mode})

		type idxResult struct {
			idx int
			sr  SiteResult
		}
		ch := make(chan idxResult, len(autoLines))
		var wg sync.WaitGroup

		for i, line := range autoLines {
			wg.Add(1)
			go func(i int, line string) {
				defer wg.Done()
				var fi, base string
				switch {
				case strings.HasPrefix(line, "+"):
					fi = strings.ReplaceAll(q, " ", "+")
					base = line[1:]
				case strings.HasPrefix(line, "-"):
					fi = strings.ReplaceAll(q, " ", "-")
					base = line[1:]
				default:
					fi = q
					base = line
				}
				su := base + fi
				found, details, logs, err := scanSite(su, q)
				sr := SiteResult{URL: su, Found: found, Details: details, Type: "auto", Logs: logs}
				if err != nil {
					sr.Error = err.Error()
					sr.Logs = append(sr.Logs, LogEntry{"warn", "Error: " + err.Error()})
				}
				ch <- idxResult{i, sr}
			}(i, line)
		}
		go func() { wg.Wait(); close(ch) }()
		for ir := range ch {
			send(map[string]interface{}{"event": "result", "result": ir.sr})
		}
	}

	// ── API sites (parallel) ──────────────────────────────────────────────────
	apiLines, _ := readLines("api_sites.txt")

	if len(apiLines) > 0 {
		send(map[string]string{"event": "section_start", "section": "api", "title": "API Sites"})

		type idxResult struct {
			idx int
			sr  SiteResult
		}
		ch := make(chan idxResult, len(apiLines))
		var wg sync.WaitGroup

		for i, line := range apiLines {
			wg.Add(1)
			go func(i int, line string) {
				defer wg.Done()
				var sr SiteResult
				if strings.HasPrefix(line, "stremio:") {
					baseURL := strings.TrimPrefix(line, "stremio:")
					mt := "series"
					if mode == "movies" {
						mt = "movie"
					}
					qe := strings.ReplaceAll(q, " ", "+")
					dispURL := baseURL + "/catalog/" + mt + "/top/search=" + qe + ".json"
					found, matches, logs, err := scanStremio(baseURL, q, mode)
					mu := ""
					if len(matches) > 0 {
						mu = matches[0].URL
					}
					sr = SiteResult{URL: dispURL, Found: found, Type: "api", MovieURL: mu, Matches: matches, Logs: logs}
					if err != nil {
						sr.Error = err.Error()
						sr.Logs = append(sr.Logs, LogEntry{"warn", "Error: " + err.Error()})
					}
				} else {
					baseURL := strings.TrimPrefix(line, "v1:")
					qe := strings.ReplaceAll(q, " ", "+")
					dispURL := baseURL + "/searching?q=" + qe + "&limit=40&offset=0"
					found, matches, logs, err := scanMovieAPI(baseURL, q)
					mu := ""
					if len(matches) > 0 {
						mu = matches[0].URL
					}
					sr = SiteResult{URL: dispURL, Found: found, Type: "api", MovieURL: mu, Matches: matches, Logs: logs}
					if err != nil {
						sr.Error = err.Error()
						sr.Logs = append(sr.Logs, LogEntry{"warn", "Error: " + err.Error()})
					}
				}
				ch <- idxResult{i, sr}
			}(i, line)
		}
		go func() { wg.Wait(); close(ch) }()
		for ir := range ch {
			send(map[string]interface{}{"event": "result", "result": ir.sr})
		}
	}

	// ── Manual sites (instant) ────────────────────────────────────────────────
	manLines, _ := readLines("manual_checks.txt")

	if len(manLines) > 0 {
		send(map[string]string{"event": "section_start", "section": "manual", "title": "Manual Checks"})
		for _, line := range manLines {
			var fi, base string
			switch {
			case strings.HasPrefix(line, "+"):
				fi = strings.ReplaceAll(q, " ", "+")
				base = line[1:]
			case strings.HasPrefix(line, "-"):
				fi = strings.ReplaceAll(q, " ", "-")
				base = line[1:]
			default:
				fi = q
				base = line
			}
			sr := SiteResult{
				URL:  base + fi,
				Type: "manual",
				Logs: []LogEntry{{"info", "Manual check — open in browser to verify"}},
			}
			send(map[string]interface{}{"event": "result", "result": sr})
		}
	}

	send(map[string]string{"event": "done"})
}

func imdbHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	q := r.URL.Query().Get("q")
	if q == "" {
		http.Error(w, `{"error":"missing query"}`, 400)
		return
	}
	results, err := searchIMDb(q)
	if err != nil {
		w.WriteHeader(500)
		json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}
	json.NewEncoder(w).Encode(map[string]interface{}{"query": q, "results": results})
}

func subtitleHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	q := r.URL.Query().Get("q")
	mode := r.URL.Query().Get("mode")
	if q == "" {
		http.Error(w, `{"error":"missing query"}`, 400)
		return
	}
	if mode != "shows" && mode != "movies" {
		mode = "shows"
	}
	resp := searchSubtitles(q, mode)
	json.NewEncoder(w).Encode(resp)
}

func configHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
	if r.Method == http.MethodOptions {
		w.WriteHeader(204)
		return
	}
	file := r.URL.Query().Get("file")
	allowed := map[string]bool{
		"shows.txt": true, "movies.txt": true,
		"manual_checks.txt": true, "api_sites.txt": true, "exclusions.txt": true,
	}
	if !allowed[file] {
		http.Error(w, `{"error":"invalid file"}`, 400)
		return
	}
	if r.Method == http.MethodGet {
		json.NewEncoder(w).Encode(map[string]string{"content": readRaw(file)})
		return
	}
	if r.Method == http.MethodPost {
		var body struct {
			Content string `json:"content"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, `{"error":"bad JSON"}`, 400)
			return
		}
		if err := os.WriteFile(file, []byte(body.Content), 0644); err != nil {
			http.Error(w, `{"error":"write failed"}`, 500)
			return
		}
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
		return
	}
	http.Error(w, `{"error":"method not allowed"}`, 405)
}

func indexHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html")
	http.ServeFile(w, r, "index.html")
}

func appJSHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/javascript")
	http.ServeFile(w, r, "app.js")
}

func main() {
	for _, f := range []string{"shows.txt", "movies.txt", "manual_checks.txt", "api_sites.txt"} {
		if _, err := os.Stat(f); os.IsNotExist(err) {
			os.WriteFile(f, []byte(""), 0644)
			log.Printf("Created empty %s", f)
		}
	}
	if _, err := os.Stat("exclusions.txt"); os.IsNotExist(err) {
		defaults := strings.Join([]string{
			"addtoany.com", "facebook.com", "twitter.com", "reddit.com",
			"pinterest.com", "whatsapp.com", "t.me", "mailto:",
			"/login", "/register", "/signup", "/feed", "#", "/filter", "/search", "/browser", "/?s=",
			"// This is a comment line",
			"// Another comment",
		}, "\n")
		os.WriteFile("exclusions.txt", []byte(defaults), 0644)
		log.Printf("Created exclusions.txt with defaults")
	}
	http.HandleFunc("/", indexHandler)
	http.HandleFunc("/app.js", appJSHandler)
	http.HandleFunc("/search", searchHandler)
	http.HandleFunc("/imdb", imdbHandler)
	http.HandleFunc("/subtitles", subtitleHandler)
	http.HandleFunc("/config", configHandler)
	port := "8080"
	log.Printf("Dvora running at http://localhost:%s", port)
	log.Fatal(http.ListenAndServe(":"+port, nil))
}
