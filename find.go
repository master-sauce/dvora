package main

import (
	"bufio"
	"fmt"
	"log"
	"os"
	"strconv"
	"strings"
)

// defineFileNames sets the file paths for shows and movies.
func defineFileNames() (string, string) {
	return "shows.txt", "movies.txt"
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

// concatUrls reads URLs from a file, formats the user's input,
// and prints the final, concatenated URLs.
func concatUrls(filePath, userInput string) {
	fmt.Printf("\nConcatenated URLs for %s:\n", strings.TrimSuffix(filePath, ".txt"))

	file, err := os.Open(filePath)
	if err != nil {
		log.Fatalf("Failed to open file %s: %v", filePath, err)
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
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

		fmt.Println(line + formattedInput)
	}

	if err := scanner.Err(); err != nil {
		log.Fatalf("Error reading from file %s: %v", filePath, err)
	}
}

// main orchestrates the program's execution flow.
func main() {
	displayWelcome()

	movshwo := getUserInput()

	showsFile, moviesFile := defineFileNames()
	checkFileExists(showsFile)
	checkFileExists(moviesFile)

	choice := getUserChoice()

	switch choice {
	case 1:
		concatUrls(showsFile, movshwo)
	case 2:
		concatUrls(moviesFile, movshwo)
	}
	fmt.Println("\nPress enter to exit...")
	readLine()
}

