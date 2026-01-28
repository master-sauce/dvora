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
	"strconv"
	"strings"
	"time"

	"golang.org/x/net/html"
)

// defineFileNames sets the file paths for shows, movies, manual checks, and API sites.
func defineFileNames() (string, string, string, string) {
	return "shows.txt", "movies.txt", "manual_checks.txt", "api_sites.txt"
}

// checkFileExists ensures a file is present at the given path.
// It logs an error and exits if the file is not found.
func checkFileExists(filePath string) {
	if _, err := os.Stat(filePath); os.IsNotExist(err) {
		log.Fatalf("File %s not found!", filePath)
	}
}

// displayWelcome shows the ASCII art and a prompt to continue.
func displayWelcome() {
	// Clear the screen (ANSI escape code, works on most modern terminals)
	fmt.Print("\033[H\033[2J")
	asciiArt := `
 ______          _______ _______ _______ 
(  __  \|\     /(  ___  |  ____ |  ___  )
| (  \  ) )   ( | (   ) | (    )| (   ) |
| |   ) | |   | | |   | | (____)| (___) |
| |   | ( (   ) ) |   | |     __)  ___  |
| |   ) |\ \_/ /| |   | | (\ (  | (   ) |
| (__/  ) \   / | (___) | ) \ \_| )   ( |
(______/   \_/  (_______)/   \__//     \|
                                         	
	`

	fmt.Println(asciiArt)
	fmt.Println("Welcome to Dvora, find your favorite movies and shows. Press enter to continue...")
	readLine() // Use a helper to wait for an empty line
}

// readLine is a helper function to read a single line from stdin,
// trimming the trailing newline.
func readLine() string {
	reader := bufio.NewReader(os.Stdin)
	line, _ := reader.ReadString('\n')
	return strings.TrimSpace(line)
}

// getUserInput prompts the user for the movie/show name.
func getUserInput() string {
	fmt.Print("Enter the movie or show to search for: ")
	return readLine()
}

// getUserChoice presents a menu and returns the user's selection.
func getUserChoice() int {
	for {
		fmt.Println("\nPlease choose an option:")
		fmt.Println("1) Use Shows File")
		fmt.Println("2) Use Movies File")
		fmt.Print("Enter your choice (1 or 2): ")
		choiceStr := readLine()
		choice, err := strconv.Atoi(choiceStr)
		if err != nil {
			fmt.Println("Invalid input. Please enter a number.")
			continue
		}
		if choice == 1 || choice == 2 {
			return choice
		}
		fmt.Println("Invalid choice. Please enter 1 or 2.")
	}
}

// getUserAgent prompts the user for their custom user agent
func getUserAgent() string {
	userAgent := "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
	return userAgent
}

// extractAllLinks extracts all href attributes from any element in the HTML
// This is more comprehensive and catches links in non-anchor elements
func extractAllLinks(n *html.Node) []string {
	var links []string

	var traverse func(*html.Node)
	traverse = func(node *html.Node) {
		// Check any element for href attributes
		if node.Type == html.ElementNode {
			for _, attr := range node.Attr {
				if attr.Key == "href" {
					links = append(links, attr.Val)
				}
			}
		}

		for c := node.FirstChild; c != nil; c = c.NextSibling {
			traverse(c)
		}
	}

	traverse(n)
	return links
}

// checkMovieAPI handles movie streaming site APIs and returns the search URL if found
func checkMovieAPI(url, searchTerm, userAgent string) (bool, string, error) {
	// Extract the base URL part before the q= parameter
	re := regexp.MustCompile(`(https://ww\d+\.[a-zA-Z0-9\-]+\.[a-zA-Z]+)/searching\?q=`)
	matches := re.FindStringSubmatch(url)
	if len(matches) < 2 {
		return false, "", fmt.Errorf("invalid movie API URL format")
	}

	baseURL := matches[1]
	// Format the search term with plus signs
	searchQuery := strings.ReplaceAll(searchTerm, " ", "+")
	// Build the complete API URL with our search term
	apiURL := baseURL + "/searching?q=" + searchQuery + "&limit=40&offset=0"

	// Create a new HTTP client with timeout
	client := &http.Client{
		Timeout: 10 * time.Second,
	}

	// Create a new request
	req, err := http.NewRequest("GET", apiURL, nil)
	if err != nil {
		return false, "", fmt.Errorf("failed to create request: %v", err)
	}

	// Set the user agent
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "application/json")

	// Make the HTTP request
	resp, err := client.Do(req)
	if err != nil {
		return false, "", fmt.Errorf("failed to fetch URL: %v", err)
	}
	defer resp.Body.Close()

	// Check if the request was successful
	if resp.StatusCode != http.StatusOK {
		return false, "", fmt.Errorf("HTTP request failed with status: %s", resp.Status)
	}

	// Read the response body
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return false, "", fmt.Errorf("failed to read response body: %v", err)
	}

	// Parse the JSON response
	var apiResponse struct {
		Data []struct {
			T string `json:"t"` // Title
			S string `json:"s"` // Slug
			D string `json:"d"` // Type (m for movie, s for series)
			E int    `json:"e"` // Episodes
			N int    `json:"n"` // New
			Q string `json:"q"` // Quality
			Y int    `json:"y"` // Year
		} `json:"data"`
		Meta struct {
			Offset     int `json:"offset"`
			TotalItems int `json:"total_items"`
			TotalPages int `json:"total_pages"`
			PageNumber int `json:"page_number"`
		} `json:"meta"`
	}

	if err := json.Unmarshal(body, &apiResponse); err != nil {
		return false, "", fmt.Errorf("failed to parse JSON response: %v", err)
	}

	// Check if any result matches our search term
	searchLower := strings.ToLower(searchTerm)
	for _, item := range apiResponse.Data {
		if strings.Contains(strings.ToLower(item.T), searchLower) {
			// Return the regular search URL format
			searchURL := baseURL + "/search/?q=" + searchQuery
			return true, searchURL, nil
		}
	}

	return false, "", nil
}

func checkSiteForContent(url, searchTerm, userAgent string) (bool, error) {
	// For regular websites
	client := &http.Client{Timeout: 10 * time.Second}
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return false, fmt.Errorf("failed to create request: %v", err)
	}
	req.Header.Set("User-Agent", userAgent)

	resp, err := client.Do(req)
	if err != nil {
		return false, fmt.Errorf("failed to fetch URL: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return false, fmt.Errorf("HTTP request failed with status: %s", resp.Status)
	}

	doc, err := html.Parse(resp.Body)
	if err != nil {
		return false, fmt.Errorf("failed to parse HTML: %v", err)
	}

	// Extract all links from the page
	links := extractAllLinks(doc)

	// More restrictive pattern matching
	searchWords := strings.Fields(strings.ToLower(searchTerm))
	if len(searchWords) == 0 {
		return false, nil
	}

	// Create a more restrictive pattern
	var patternBuilder strings.Builder
	for i, word := range searchWords {
		if i > 0 {
			patternBuilder.WriteString(`[\s\-\+\.\/]+`)
		}
		patternBuilder.WriteString(regexp.QuoteMeta(word))
	}
	patternStr := patternBuilder.String()
	searchPattern := regexp.MustCompile(patternStr)

	// Count how many links match our pattern
	matchCount := 0
	for _, link := range links {
		linkLower := strings.ToLower(link)

		// Skip external social sharing and irrelevant links
		if strings.Contains(linkLower, "addtoany.com") ||
			strings.Contains(linkLower, "facebook.com") ||
			strings.Contains(linkLower, "twitter.com") ||
			strings.Contains(linkLower, "reddit.com") ||
			strings.Contains(linkLower, "pinterest.com") ||
			strings.Contains(linkLower, "whatsapp.com") ||
			strings.Contains(linkLower, "t.me") ||
			strings.Contains(linkLower, "mailto:") ||
			strings.Contains(linkLower, "/login") ||
			strings.Contains(linkLower, "/register") ||
			strings.Contains(linkLower, "/signup") {
			continue
		}

		if searchPattern.MatchString(linkLower) {
			matchCount++
		}
	}

	// If we found matching links, return true regardless of any "no results" text
	// This prevents false positives where the text exists but results are actually shown
	if matchCount > 0 {
		return true, nil
	}

	// Only if we found NO matching links, then check if the page explicitly says "no results"
	if hasNoResults(doc) {
		return false, nil
	}

	// No matches found and no explicit "no results" message
	return false, nil
}

// hasNoResults checks if the page indicates no results were found
func hasNoResults(doc *html.Node) bool {
	var pageText strings.Builder
	var traverseText func(*html.Node)
	traverseText = func(node *html.Node) {
		if node.Type == html.TextNode {
			pageText.WriteString(node.Data)
			pageText.WriteString(" ")
		}
		for c := node.FirstChild; c != nil; c = c.NextSibling {
			traverseText(c)
		}
	}
	traverseText(doc)
	pageContent := strings.ToLower(pageText.String())

	// Check for common "no results" indicators
	// All indicators should be lowercase since pageContent is converted to lowercase
	noResultsIndicators := []string{
		"no result found.", // with period (soap2day uses this)
		"no result found",  // without period
		"no results found", // plural
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

	for _, indicator := range noResultsIndicators {
		if strings.Contains(pageContent, indicator) {
			return true
		}
	}
	return false
}

// searchAndCheckUrls checks regular (non-API) sites
func searchAndCheckUrls(filePath, userInput, userAgent string) {
	fmt.Printf("\nSearching for '%s' in %s:\n", userInput, strings.TrimSuffix(filePath, ".txt"))
	file, err := os.Open(filePath)
	if err != nil {
		log.Fatalf("Failed to open file %s: %v", filePath, err)
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	foundAny := false

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}

		var formattedInput string
		var url string

		// Regular URL handling
		switch {
		case strings.HasPrefix(line, "+"):
			formattedInput = strings.ReplaceAll(userInput, " ", "+")
			line = line[1:] // Remove prefix
		case strings.HasPrefix(line, "-"):
			formattedInput = strings.ReplaceAll(userInput, " ", "-")
			line = line[1:] // Remove prefix
		default:
			formattedInput = userInput
		}
		url = line + formattedInput

		fmt.Printf("Checking: %s\n", url)

		contains, err := checkSiteForContent(url, userInput, userAgent)
		if err != nil {
			fmt.Printf("Error checking %s: %v\n", url, err)
			continue
		}

		if contains {
			fmt.Printf("✓ Found '%s' on this site!\n", userInput)
			foundAny = true
		} else {
			fmt.Printf("✗ '%s' not found on this site.\n", userInput)
		}
	}

	if err := scanner.Err(); err != nil {
		log.Fatalf("Error reading from file %s: %v", filePath, err)
	}

	if !foundAny {
		fmt.Printf("'%s' was not found on any of the sites.\n", userInput)
	}
}

// searchAndCheckAPIUrls checks API-based sites and returns actual movie URLs
func searchAndCheckAPIUrls(filePath, userInput, userAgent string) {
	file, err := os.Open(filePath)
	if err != nil {
		// If file doesn't exist, just skip silently
		return
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)

	// Check if file has any content
	hasContent := false
	var lines []string
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line != "" {
			hasContent = true
			lines = append(lines, line)
		}
	}

	if !hasContent {
		return
	}

	// Print separator for API results
	fmt.Print("\n" + strings.Repeat("=", 60) + "\n")
	fmt.Print("API SITE RESULTS:\n\n")

	for _, line := range lines {
		// Build API URL
		formattedInput := strings.ReplaceAll(userInput, " ", "+")
		apiURL := line + "/searching?q=" + formattedInput + "&limit=40&offset=0"

		fmt.Printf("Checking: %s\n", apiURL)

		found, movieURL, err := checkMovieAPI(apiURL, userInput, userAgent)
		if err != nil {
			fmt.Printf("Error checking %s: %v\n", apiURL, err)
			continue
		}

		if found {
			fmt.Printf("✓ Found '%s' on this site!\n", userInput)
			fmt.Printf("  → %s\n", movieURL)
		} else {
			fmt.Printf("✗ '%s' not found on this site.\n", userInput)
		}
	}

	if err := scanner.Err(); err != nil {
		log.Fatalf("Error reading from file %s: %v", filePath, err)
	}
}

// searchManualChecks reads URLs from manual_checks.txt, formats the user input
// based on prefixes (+ or -), and prints the formatted URLs to console.
func searchManualChecks(filePath, userInput string) {
	fmt.Print("\n" + strings.Repeat("=", 60) + "\n")
	fmt.Print("MANUAL CHECKS: \n\n")

	file, err := os.Open(filePath)
	if err != nil {
		fmt.Printf("Warning: Could not open %s: %v\n", filePath, err)
		return
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	urlCount := 0

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())

		if line == "" {
			continue // Skip empty lines
		}

		var formattedInput string
		switch {
		case strings.HasPrefix(line, "+"):
			formattedInput = strings.ReplaceAll(userInput, " ", "+")
			line = line[1:] // Remove prefix
		case strings.HasPrefix(line, "-"):
			formattedInput = strings.ReplaceAll(userInput, " ", "-")
			line = line[1:] // Remove prefix
		default:
			formattedInput = userInput
		}

		url := line + formattedInput
		urlCount++
		fmt.Printf("%d. %s\n", urlCount, url)
	}

	if err := scanner.Err(); err != nil {
		fmt.Printf("Error reading from file %s: %v\n", filePath, err)
	}

	if urlCount == 0 {
		fmt.Println("No URLs found in manual_checks.txt")
	}

	fmt.Print("\n" + strings.Repeat("=", 60) + "\n")
}

// main orchestrates the program's execution flow.
func main() {
	displayWelcome()
	movshwo := getUserInput()

	showsFile, moviesFile, manualChecksFile, apiSitesFile := defineFileNames()
	checkFileExists(showsFile)
	checkFileExists(moviesFile)
	checkFileExists(manualChecksFile)
	// API sites file is optional, so we don't check if it exists

	// Get user agent
	userAgent := getUserAgent()

	choice := getUserChoice()

	switch choice {
	case 1:
		searchAndCheckUrls(showsFile, movshwo, userAgent)
	case 2:
		searchAndCheckUrls(moviesFile, movshwo, userAgent)
	}

	// Check API sites
	searchAndCheckAPIUrls(apiSitesFile, movshwo, userAgent)

	// Always run manual checks at the end, regardless of choice
	searchManualChecks(manualChecksFile, movshwo)

	fmt.Println("\nPress enter to exit...")
	readLine()
}
