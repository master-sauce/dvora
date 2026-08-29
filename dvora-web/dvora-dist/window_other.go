//go:build !windows

package main

import (
	"fmt"
	"log"
)

// openUI on Linux/macOS opens the default web browser and blocks forever,
// keeping the server alive until the user hits Ctrl+C.
func openUI(url string) {
	log.Printf("Opening %s in default browser", url)
	fmt.Printf("DVORA running at %s — press Ctrl+C to stop\n", url)
	openBrowser(url)
	select {} // keep server alive
}
