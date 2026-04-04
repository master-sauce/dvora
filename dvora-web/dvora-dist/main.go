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

// Wizdom
type wizdomItem struct {
	Title   string `json:"title"`
	TitleEn string `json:"title_en"`
	Imdb    string `json:"imdb"`
	Type    string `json:"type"`
}

// Subtitle results
type SubResult struct {
	URL     string `json:"url"`
	Found   bool   `json:"found"`
	Title   string `json:"title"`
	ImdbID  string `json:"imdbId"`
	Details string `json:"details,omitempty"`
	Error   string `json:"error,omitempty"`
}

type SubResponse struct {
	Query   string      `json:"query"`
	Mode    string      `json:"mode"`
	Results []SubResult `json:"results"`
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

	skip := []string{"addtoany.com", "facebook.com", "twitter.com", "reddit.com",
		"pinterest.com", "whatsapp.com", "t.me", "mailto:", "/login", "/register", "/signup", "/feed", "#"}

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
		if !s && (strings.HasPrefix(ll, "/search/") || strings.HasPrefix(ll, "search/") || strings.HasPrefix(ll, "/search?")) {
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

// ─── Movie API (v1) ───────────────────────────────────────────────────────────

func scanMovieAPI(baseURL, searchTerm string) (bool, []MovieMatch, []LogEntry, error) {
	var logs []LogEntry
	add := func(lvl, msg string) { logs = append(logs, LogEntry{lvl, msg}) }

	q := strings.ReplaceAll(searchTerm, " ", "+")
	apiURL := baseURL + "/searching?q=" + q + "&limit=40&offset=0"
	add("info", "API GET "+apiURL)

	client := &http.Client{Timeout: 10 * time.Second}
	req, _ := http.NewRequest("GET", apiURL, nil)
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		return false, nil, logs, err
	}
	defer resp.Body.Close()
	add("info", fmt.Sprintf("HTTP %d", resp.StatusCode))
	if resp.StatusCode != 200 {
		return false, nil, logs, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	body, _ := io.ReadAll(resp.Body)

	var ar struct {
		Data []struct {
			T string `json:"t"`
			Y int    `json:"y"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &ar); err != nil {
		return false, nil, logs, err
	}
	add("info", fmt.Sprintf("%d results", len(ar.Data)))

	sl := strings.ToLower(searchTerm)
	searchURL := baseURL + "/search/?q=" + q
	var matches []MovieMatch
	for _, item := range ar.Data {
		if len(matches) >= 10 {
			break
		}
		if strings.Contains(strings.ToLower(item.T), sl) {
			matches = append(matches, MovieMatch{Name: item.T, URL: searchURL})
			add("match", fmt.Sprintf(`✓ "%s" (%d)`, item.T, item.Y))
		} else {
			add("skip", fmt.Sprintf(`  no match: "%s"`, item.T))
		}
	}
	if len(matches) > 0 {
		add("verdict", fmt.Sprintf("FOUND — %d result(s)", len(matches)))
		return true, matches, logs, nil
	}
	add("verdict", fmt.Sprintf("NOT FOUND — 0 of %d matched", len(ar.Data)))
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
	q := strings.ReplaceAll(searchTerm, " ", "+")
	apiURL := baseURL + "/catalog/" + mt + "/top/search=" + q + ".json"
	add("info", "Stremio GET "+apiURL)

	client := &http.Client{Timeout: 10 * time.Second}
	req, _ := http.NewRequest("GET", apiURL, nil)
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		return false, nil, logs, err
	}
	defer resp.Body.Close()
	add("info", fmt.Sprintf("HTTP %d", resp.StatusCode))
	if resp.StatusCode != 200 {
		return false, nil, logs, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	body, _ := io.ReadAll(resp.Body)

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
		return false, nil, logs, err
	}
	add("info", fmt.Sprintf("%d results", len(ar.Metas)))

	sl := strings.ToLower(searchTerm)
	var matches []MovieMatch
	for _, item := range ar.Metas {
		if len(matches) >= 10 {
			break
		}
		if strings.Contains(strings.ToLower(item.Name), sl) {
			id := item.ImdbID
			if id == "" {
				id = item.ID
			}
			su := "https://web.stremio.com/#/detail/" + item.Type + "/" + id + "/" + id
			matches = append(matches, MovieMatch{Name: item.Name, URL: su})
			add("match", fmt.Sprintf(`✓ "%s" (%s)`, item.Name, item.ReleaseInfo))
		} else {
			add("skip", fmt.Sprintf(`  no match: "%s"`, item.Name))
		}
	}
	if len(matches) > 0 {
		add("verdict", fmt.Sprintf("FOUND — %d result(s)", len(matches)))
		return true, matches, logs, nil
	}
	add("verdict", fmt.Sprintf("NOT FOUND — 0 of %d matched", len(ar.Metas)))
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

func searchSubtitles(searchTerm, mode string) SubResponse {
	typePath := "tv"
	if mode == "movies" {
		typePath = "movie"
	}

	wizdomIds := make(map[string]string)

	wizdomAPIURL := "https://wizdom.xyz/api/search?search=" + url.QueryEscape(searchTerm) + "&page=0"
	client := &http.Client{Timeout: 10 * time.Second}
	if req, err := http.NewRequest("GET", wizdomAPIURL, nil); err == nil {
		req.Header.Set("User-Agent", userAgent)
		if resp, err := client.Do(req); err == nil {
			defer resp.Body.Close()
			if resp.StatusCode == 200 {
				body, _ := io.ReadAll(resp.Body)
				var items []wizdomItem
				if json.Unmarshal(body, &items) == nil {
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
				}
			}
		}
	}

	if imdbResults, err := searchIMDb(searchTerm); err == nil {
		for _, r := range imdbResults {
			if _, exists := wizdomIds[r.ImdbID]; !exists {
				wizdomIds[r.ImdbID] = r.Title
			}
		}
	}

	var results []SubResult
	if len(wizdomIds) == 0 {
		results = append(results, SubResult{
			URL:     wizdomAPIURL,
			Found:   false,
			Details: "No matching titles found on Wizdom or IMDb",
		})
	} else {
		for imdbID, title := range wizdomIds {
			finalURL := "https://wizdom.xyz/" + typePath + "/" + imdbID
			results = append(results, SubResult{
				URL:    finalURL,
				Found:  true,
				Title:  title,
				ImdbID: imdbID,
			})
		}
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
		if line != "" && !strings.HasPrefix(line, "#") {
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
		"manual_checks.txt": true, "api_sites.txt": true,
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
		var body struct{ Content string `json:"content"` }
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

func main() {
	for _, f := range []string{"shows.txt", "movies.txt", "manual_checks.txt", "api_sites.txt"} {
		if _, err := os.Stat(f); os.IsNotExist(err) {
			os.WriteFile(f, []byte(""), 0644)
			log.Printf("Created empty %s", f)
		}
	}
	http.HandleFunc("/", indexHandler)
	http.HandleFunc("/search", searchHandler)
	http.HandleFunc("/imdb", imdbHandler)
	http.HandleFunc("/subtitles", subtitleHandler)
	http.HandleFunc("/config", configHandler)
	port := "8080"
	log.Printf("Dvora running at http://localhost:%s", port)
	log.Fatal(http.ListenAndServe(":"+port, nil))
}