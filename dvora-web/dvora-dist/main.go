package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"regexp"
	"strings"
	"time"
)

var userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

// --- Types ---

type LogEntry struct {
	Level   string `json:"level"` // "info", "match", "skip", "warn", "verdict"
	Message string `json:"message"`
}

type SiteResult struct {
	URL       string     `json:"url"`
	Found     bool       `json:"found"`
	Error     string     `json:"error,omitempty"`
	Type      string     `json:"type"` // "auto", "api", "manual"
	MovieURL  string     `json:"movie_url,omitempty"`
	MovieURLs []string   `json:"movie_urls,omitempty"`
	Logs      []LogEntry `json:"logs"`
}

type SearchResponse struct {
	Query   string       `json:"query"`
	Results []SiteResult `json:"results"`
}

// --- HTML helpers ---

func extractLinksFromHTML(body []byte) []string {
	re := regexp.MustCompile(`(?i)href=["']([^"']+)["']`)
	matches := re.FindAllSubmatch(body, -1)
	var links []string
	for _, m := range matches {
		if len(m) > 1 {
			links = append(links, string(m[1]))
		}
	}
	return links
}

func findNoResultsIndicator(body []byte) string {
	lower := strings.ToLower(string(body))
	indicators := []string{
		"no result found.",
		"no result found",
		"no results found",
		"no results",
		"nothing found",
		"not found",
		"no matches",
		"0 results",
		"could not find",
		"couldn't find",
		"search returned no results",
		"sorry, no results",
		"no items found",
		"your search did not match",
		"did not match any",
		"no search results",
	}
	for _, ind := range indicators {
		if strings.Contains(lower, ind) {
			return ind
		}
	}
	return ""
}

// --- Core Logic ---

func checkSiteForContent(url, searchTerm string) (bool, []LogEntry, error) {
	var logs []LogEntry
	add := func(level, msg string) { logs = append(logs, LogEntry{level, msg}) }

	client := &http.Client{Timeout: 10 * time.Second}
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return false, logs, err
	}
	req.Header.Set("User-Agent", userAgent)

	add("info", fmt.Sprintf("GET %s", url))

	resp, err := client.Do(req)
	if err != nil {
		return false, logs, err
	}
	defer resp.Body.Close()

	add("info", fmt.Sprintf("Response: HTTP %d %s", resp.StatusCode, resp.Status))

	if resp.StatusCode != http.StatusOK {
		return false, logs, fmt.Errorf("HTTP %s", resp.Status)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return false, logs, err
	}

	links := extractLinksFromHTML(body)
	add("info", fmt.Sprintf("Extracted %d href links from page HTML", len(links)))

	searchWords := strings.Fields(strings.ToLower(searchTerm))
	if len(searchWords) == 0 {
		return false, logs, nil
	}

	// Build regex pattern
	var patternBuilder strings.Builder
	for i, word := range searchWords {
		if i > 0 {
			patternBuilder.WriteString(`[\s\-\+\.\/]+`)
		}
		patternBuilder.WriteString(regexp.QuoteMeta(word))
	}
	patternStr := patternBuilder.String()
	searchPattern := regexp.MustCompile(patternStr)
	add("info", fmt.Sprintf("Built regex pattern: /%s/", patternStr))

	skipDomains := []string{
		"addtoany.com", "facebook.com", "twitter.com", "reddit.com",
		"pinterest.com", "whatsapp.com", "t.me", "mailto:", "/login",
		"/register", "/signup",
	}

	matchCount := 0
	skippedCount := 0
	var matchedLinks []string

	for _, link := range links {
		linkLower := strings.ToLower(link)

		skip := false
		skipReason := ""
		for _, d := range skipDomains {
			if strings.Contains(linkLower, d) {
				skip = true
				skipReason = "blocklist: " + d
				break
			}
		}
		if !skip {
			if strings.HasPrefix(linkLower, "/search/") ||
				strings.HasPrefix(linkLower, "search/") ||
				strings.HasPrefix(linkLower, "/search?") {
				skip = true
				skipReason = "search/pagination path"
			}
		}

		if skip {
			skippedCount++
			_ = skipReason
			continue
		}

		if searchPattern.MatchString(linkLower) {
			matchCount++
			matchedLinks = append(matchedLinks, link)
		}
	}

	add("info", fmt.Sprintf("Skipped %d links (social/auth/search paths)", skippedCount))
	add("info", fmt.Sprintf("Pattern matched %d of remaining links", matchCount))

	if matchCount > 0 {
		for i, ml := range matchedLinks {
			if i >= 5 {
				add("match", fmt.Sprintf("  ... and %d more matching links", len(matchedLinks)-5))
				break
			}
			add("match", fmt.Sprintf("✓ MATCH: %s", ml))
		}
		add("verdict", fmt.Sprintf("FOUND — %d link(s) matched regex /%s/", matchCount, patternStr))
		return true, logs, nil
	}

	if indicator := findNoResultsIndicator(body); indicator != "" {
		add("warn", fmt.Sprintf("Page contains no-results text: \"%s\"", indicator))
		add("verdict", fmt.Sprintf("NOT FOUND — 0 link matches + explicit no-results indicator: \"%s\"", indicator))
		return false, logs, nil
	}

	add("verdict", fmt.Sprintf("NOT FOUND — 0 links matched regex /%s/ and no no-results text detected", patternStr))
	return false, logs, nil
}

func checkMovieAPI(baseURL, searchTerm string) (bool, string, []LogEntry, error) {
	var logs []LogEntry
	add := func(level, msg string) { logs = append(logs, LogEntry{level, msg}) }

	searchQuery := strings.ReplaceAll(searchTerm, " ", "+")
	apiURL := baseURL + "/searching?q=" + searchQuery + "&limit=40&offset=0"

	add("info", fmt.Sprintf("API GET %s", apiURL))

	client := &http.Client{Timeout: 10 * time.Second}
	req, err := http.NewRequest("GET", apiURL, nil)
	if err != nil {
		return false, "", logs, err
	}
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		return false, "", logs, err
	}
	defer resp.Body.Close()

	add("info", fmt.Sprintf("Response: HTTP %d %s", resp.StatusCode, resp.Status))

	if resp.StatusCode != http.StatusOK {
		return false, "", logs, fmt.Errorf("HTTP %s", resp.Status)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return false, "", logs, err
	}

	var apiResponse struct {
		Data []struct {
			T string `json:"t"`
			Y int    `json:"y"`
			D string `json:"d"`
		} `json:"data"`
		Meta struct {
			TotalItems int `json:"total_items"`
		} `json:"meta"`
	}
	if err := json.Unmarshal(body, &apiResponse); err != nil {
		return false, "", logs, fmt.Errorf("JSON parse error: %v", err)
	}

	add("info", fmt.Sprintf("API returned %d results (total_items: %d)", len(apiResponse.Data), apiResponse.Meta.TotalItems))

	searchLower := strings.ToLower(searchTerm)
	add("info", fmt.Sprintf("Checking each title for substring: \"%s\"", searchLower))

	for _, item := range apiResponse.Data {
		titleLower := strings.ToLower(item.T)
		if strings.Contains(titleLower, searchLower) {
			movieURL := baseURL + "/search/?q=" + searchQuery
			add("match", fmt.Sprintf("✓ MATCH: \"%s\" (%s %d) — contains \"%s\"", item.T, item.D, item.Y, searchLower))
			add("verdict", fmt.Sprintf("FOUND — \"%s\" matched search term", item.T))
			return true, movieURL, logs, nil
		}
		add("skip", fmt.Sprintf("  no match: \"%s\"", item.T))
	}

	add("verdict", fmt.Sprintf("NOT FOUND — none of %d API results matched \"%s\"", len(apiResponse.Data), searchTerm))
	return false, "", logs, nil
}

func checkStremioAPI(baseURL, searchTerm, mode string) (bool, []string, []LogEntry, error) {
	var logs []LogEntry
	add := func(level, msg string) { logs = append(logs, LogEntry{level, msg}) }

	mediaType := "series"
	if mode == "movies" {
		mediaType = "movie"
	}

	searchQuery := strings.ReplaceAll(searchTerm, " ", "+")
	apiURL := baseURL + "/catalog/" + mediaType + "/top/search=" + searchQuery + ".json"

	add("info", fmt.Sprintf("Stremio API GET %s", apiURL))

	client := &http.Client{Timeout: 10 * time.Second}
	req, err := http.NewRequest("GET", apiURL, nil)
	if err != nil {
		return false, nil, logs, err
	}
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		return false, nil, logs, err
	}
	defer resp.Body.Close()

	add("info", fmt.Sprintf("Response: HTTP %d %s", resp.StatusCode, resp.Status))

	if resp.StatusCode != http.StatusOK {
		return false, nil, logs, fmt.Errorf("HTTP %s", resp.Status)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return false, nil, logs, err
	}

	var apiResponse struct {
		Metas []struct {
			ID          string `json:"id"`
			ImdbID      string `json:"imdb_id"`
			Type        string `json:"type"`
			Name        string `json:"name"`
			ReleaseInfo string `json:"releaseInfo"`
		} `json:"metas"`
	}
	if err := json.Unmarshal(body, &apiResponse); err != nil {
		return false, nil, logs, fmt.Errorf("JSON parse error: %v", err)
	}

	add("info", fmt.Sprintf("API returned %d results", len(apiResponse.Metas)))

	searchLower := strings.ToLower(searchTerm)
	add("info", fmt.Sprintf("Checking each name for substring: \"%s\"", searchLower))

	var matchedURLs []string
	for _, item := range apiResponse.Metas {
		if len(matchedURLs) >= 10 {
			break
		}
		nameLower := strings.ToLower(item.Name)
		if strings.Contains(nameLower, searchLower) {
			id := item.ImdbID
			if id == "" {
				id = item.ID
			}
			stremioURL := "https://web.stremio.com/#/detail/" + item.Type + "/" + id + "/" + id
			matchedURLs = append(matchedURLs, stremioURL)
			add("match", fmt.Sprintf("✓ MATCH: \"%s\" (%s) → %s", item.Name, item.ReleaseInfo, stremioURL))
		} else {
			add("skip", fmt.Sprintf("  no match: \"%s\"", item.Name))
		}
	}

	if len(matchedURLs) > 0 {
		add("verdict", fmt.Sprintf("FOUND — %d result(s) matched \"%s\"", len(matchedURLs), searchTerm))
		return true, matchedURLs, logs, nil
	}

	add("verdict", fmt.Sprintf("NOT FOUND — none of %d results matched \"%s\"", len(apiResponse.Metas), searchTerm))
	return false, nil, logs, nil
}

func readLines(filePath string) ([]string, error) {
	f, err := os.Open(filePath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	defer f.Close()

	var lines []string
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line != "" && !strings.HasPrefix(line, "#") {
			lines = append(lines, line)
		}
	}
	return lines, scanner.Err()
}

func readRaw(filePath string) string {
	b, err := os.ReadFile(filePath)
	if err != nil {
		return ""
	}
	return string(b)
}

// --- HTTP Handlers ---

func searchHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Access-Control-Allow-Origin", "*")

	query := r.URL.Query().Get("q")
	mode := r.URL.Query().Get("mode")

	if query == "" {
		http.Error(w, `{"error":"missing query"}`, 400)
		return
	}
	if mode != "shows" && mode != "movies" {
		http.Error(w, `{"error":"invalid mode"}`, 400)
		return
	}

	var results []SiteResult

	autoFile := "shows.txt"
	if mode == "movies" {
		autoFile = "movies.txt"
	}

	autoLines, _ := readLines(autoFile)
	for _, line := range autoLines {
		var formattedInput, base string
		switch {
		case strings.HasPrefix(line, "+"):
			formattedInput = strings.ReplaceAll(query, " ", "+")
			base = line[1:]
		case strings.HasPrefix(line, "-"):
			formattedInput = strings.ReplaceAll(query, " ", "-")
			base = line[1:]
		default:
			formattedInput = query
			base = line
		}
		url := base + formattedInput

		found, logs, err := checkSiteForContent(url, query)
		sr := SiteResult{URL: url, Found: found, Type: "auto", Logs: logs}
		if err != nil {
			sr.Error = err.Error()
			sr.Logs = append(sr.Logs, LogEntry{"warn", "Error: " + err.Error()})
		}
		results = append(results, sr)
	}

	apiLines, _ := readLines("api_sites.txt")
	for _, line := range apiLines {
		var found bool
		var movieURL string
		var logs []LogEntry
		var err error
		var displayURL string

		switch {
		case strings.HasPrefix(line, "stremio:"):
			baseURL := strings.TrimPrefix(line, "stremio:")
			mediaType := "series"
			if mode == "movies" {
				mediaType = "movie"
			}
			searchQuery := strings.ReplaceAll(query, " ", "+")
			displayURL = baseURL + "/catalog/" + mediaType + "/top/search=" + searchQuery + ".json"
			var matchedURLs []string
			found, matchedURLs, logs, err = checkStremioAPI(baseURL, query, mode)
			if len(matchedURLs) > 0 {
				movieURL = matchedURLs[0]
			}
			sr := SiteResult{URL: displayURL, Found: found, Type: "api", MovieURL: movieURL, MovieURLs: matchedURLs, Logs: logs}
			if err != nil {
				sr.Error = err.Error()
				sr.Logs = append(sr.Logs, LogEntry{"warn", "Error: " + err.Error()})
			}
			results = append(results, sr)
			continue
		default:
			// cinemeta: prefix or bare URL — original logic
			baseURL := strings.TrimPrefix(line, "cinemeta:")
			searchQuery := strings.ReplaceAll(query, " ", "+")
			displayURL = baseURL + "/searching?q=" + searchQuery + "&limit=40&offset=0"
			found, movieURL, logs, err = checkMovieAPI(baseURL, query)
		}

		sr := SiteResult{URL: displayURL, Found: found, Type: "api", MovieURL: movieURL, Logs: logs}
		if err != nil {
			sr.Error = err.Error()
			sr.Logs = append(sr.Logs, LogEntry{"warn", "Error: " + err.Error()})
		}
		results = append(results, sr)
	}

	manualLines, _ := readLines("manual_checks.txt")
	for _, line := range manualLines {
		var formattedInput, base string
		switch {
		case strings.HasPrefix(line, "+"):
			formattedInput = strings.ReplaceAll(query, " ", "+")
			base = line[1:]
		case strings.HasPrefix(line, "-"):
			formattedInput = strings.ReplaceAll(query, " ", "-")
			base = line[1:]
		default:
			formattedInput = query
			base = line
		}
		url := base + formattedInput
		results = append(results, SiteResult{
			URL:  url,
			Type: "manual",
			Logs: []LogEntry{{"info", "Manual check — visit this URL directly to verify"}},
		})
	}

	resp := SearchResponse{Query: query, Results: results}
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
		content := readRaw(file)
		json.NewEncoder(w).Encode(map[string]string{"content": content})
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

func main() {
	for _, f := range []string{"shows.txt", "movies.txt", "manual_checks.txt", "api_sites.txt"} {
		if _, err := os.Stat(f); os.IsNotExist(err) {
			os.WriteFile(f, []byte(""), 0644)
			log.Printf("Created empty %s", f)
		}
	}

	http.HandleFunc("/", indexHandler)
	http.HandleFunc("/search", searchHandler)
	http.HandleFunc("/config", configHandler)

	port := "8080"
	log.Printf("Dvora running at http://localhost:%s", port)
	log.Fatal(http.ListenAndServe(":"+port, nil))
}