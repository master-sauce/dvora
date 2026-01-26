package main

import (
	"bufio"
	"fmt"
	"log"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"

	"golang.org/x/net/html"
)

// defineFileNames sets the file paths for shows, movies, and manual checks.
func defineFileNames() (string, string, string) {
	return "shows.txt", "movies.txt", "manual_checks.txt"
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
	// fmt.Print("Enter your custom user agent (press Enter for default): ")
	// userAgent := readLine()
	// if userAgent == "" {
	// 	// Default user agent
	// 	userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
	// }

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

// checkSiteForContent fetches the content of a URL and checks if any links contain the search term
func checkSiteForContent(url, searchTerm, userAgent string) (bool, error) {
	// Create a new HTTP client with timeout
	client := &http.Client{
		Timeout: 10 * time.Second,
	}

	// Create a new request
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return false, fmt.Errorf("failed to create request: %v", err)
	}

	// Set the user agent
	req.Header.Set("User-Agent", userAgent)

	// Make the HTTP request
	resp, err := client.Do(req)
	if err != nil {
		return false, fmt.Errorf("failed to fetch URL: %v", err)
	}
	defer resp.Body.Close()

	// Check if the request was successful
	if resp.StatusCode != http.StatusOK {
		return false, fmt.Errorf("HTTP request failed with status: %s", resp.Status)
	}

	// Parse the HTML
	doc, err := html.Parse(resp.Body)
	if err != nil {
		return false, fmt.Errorf("failed to parse HTML: %v", err)
	}

	// Extract all links from the page
	links := extractAllLinks(doc)

	// Prepare patterns to match the search term with spaces, hyphens, or plus signs between words
	searchWords := strings.Fields(strings.ToLower(searchTerm))
	if len(searchWords) == 0 {
		return false, nil
	}

	// Create multiple patterns to match the search term in different formats
	var patterns []string

	// Pattern 1: Exact match with word boundaries
	var pattern1Builder strings.Builder
	for i, word := range searchWords {
		if i > 0 {
			pattern1Builder.WriteString(`[\s\-\+\.]+`)
		}
		pattern1Builder.WriteString(regexp.QuoteMeta(word))
	}
	patterns = append(patterns, pattern1Builder.String())

	// Pattern 2: More flexible match with any characters before and after
	var pattern2Builder strings.Builder
	pattern2Builder.WriteString(`.*`)
	for i, word := range searchWords {
		if i > 0 {
			pattern2Builder.WriteString(`[\s\-\+\.\/]+`)
		}
		pattern2Builder.WriteString(regexp.QuoteMeta(word))
	}
	pattern2Builder.WriteString(`.*`)
	patterns = append(patterns, pattern2Builder.String())

	// Pattern 3: Even more flexible match with any characters including numbers
	var pattern3Builder strings.Builder
	pattern3Builder.WriteString(`.*`)
	for i, word := range searchWords {
		if i > 0 {
			pattern3Builder.WriteString(`[\s\-\+\.\/\d]+`)
		}
		pattern3Builder.WriteString(regexp.QuoteMeta(word))
	}
	pattern3Builder.WriteString(`.*`)
	patterns = append(patterns, pattern3Builder.String())

	// Test each pattern
	for _, patternStr := range patterns {
		searchPattern := regexp.MustCompile(patternStr)

		// Check each link for a match
		for _, link := range links {
			linkLower := strings.ToLower(link)
			if searchPattern.MatchString(linkLower) {
				return true, nil
			}
		}
	}

	return false, nil
}

// searchAndCheckUrls reads URLs from a file, formats the user's input,
// and checks each site for the content.
func searchAndCheckUrls(filePath, userInput, userAgent string) {
	fmt.Printf("\nSearching for '%s' in %s:\n", userInput, strings.TrimSuffix(filePath, ".txt"))

	file, err := os.Open(filePath)
	if err != nil {
		log.Fatalf("Failed to open file %s: %v", filePath, err)
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)

	// Track if any site contains the content
	foundAny := false
	lineNum := 0

	for scanner.Scan() {
		lineNum++
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
		fmt.Printf("Checking: %s\n", url)

		// Check the site for the content
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

// searchManualChecks reads URLs from manual_checks.txt, formats the user input
// based on prefixes (+ or -), and prints the formatted URLs to console.
func searchManualChecks(filePath, userInput string) {
	fmt.Print("\n" + strings.Repeat("=", 60) + "\n")
	fmt.Print("MANUAL CHECKS: \n\n")
	// fmt.Print(strings.Repeat("=", 60) + "\n\n")

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

	showsFile, moviesFile, manualChecksFile := defineFileNames()
	checkFileExists(showsFile)
	checkFileExists(moviesFile)
	checkFileExists(manualChecksFile)

	// Get user agent
	userAgent := getUserAgent()

	choice := getUserChoice()

	switch choice {
	case 1:
		searchAndCheckUrls(showsFile, movshwo, userAgent)
	case 2:
		searchAndCheckUrls(moviesFile, movshwo, userAgent)
	}

	// Always run manual checks at the end, regardless of choice
	searchManualChecks(manualChecksFile, movshwo)

	fmt.Println("\nPress enter to exit...")
	readLine()
}
