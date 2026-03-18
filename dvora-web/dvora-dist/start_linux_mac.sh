#!/bin/bash

# Make sure the script works from any directory
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

echo "Starting DVORA..."

# Start the server in background
go run main.go &

# Wait for server to start
sleep 2

# Open in default browser (works on most Linux distros and macOS)
if command -v xdg-open > /dev/null; then
    xdg-open http://localhost:8080
elif command -v open > /dev/null; then
    open http://localhost:8080
else
    echo "Please open http://localhost:8080 in your browser"
fi

echo "DVORA is now running at http://localhost:8080"
read -p "Press Enter to stop the server..."
