#!/bin/bash

# Display custom ASCII art welcome message
clear
cat << "EOF"
 ______     ,---.  ,---.   ,-----.    .-------.       ____     
|    _ `''. |   /  |   | .'  .-,  '.  |  _ _   \    .'  __ `.  
| _ | ) _  \|  |   |  .'/ ,-.|  \ _ \ | ( ' )  |   /   '  \  \ 
|( ''_'  ) ||  | _ |  |;  \  '_ /  | :|(_ o _) /   |___|  /  | 
| . (_) `. ||  _( )_  ||  _`,/ \ _/  || (_,_).' __    _.-`   | 
|(_    ._) '\ (_ o._) /: (  '\_/ \   ;|  |\ \  |  |.'   _    | 
|  (_.\.' /  \ (_,_) /  \ `"/  \  ) / |  | \ `'   /|  _( )_  | 
|       .'    \     /    '. \_/``".'  |  |  \    / \ (_ o _) / 
'-----'`       `---`       '-----'    ''-'   `'-'   '.(_,_).'  
                                                               
EOF

# Prompt to press any key to continue
echo "Welcome to Dvora, find your favorite movies and shows. Press enter to continue..."
read -n 1 -s  # Waits for a single key press without showing input

# Prompt for user input
read -p "Enter the movie or show to search for: " MOVSHWO

# Define the file names
shows_file="shows.txt"
movies_file="movies.txt"

# Check if the files exist
if [ ! -f "$shows_file" ]; then
    echo "File $shows_file not found!"
    exit 1
fi

if [ ! -f "$movies_file" ]; then
    echo "File $movies_file not found!"
    exit 1
fi

# Function to concatenate input with URLs from a file
concat_urls() {
    local file="$1"
    while IFS= read -r url; do
        # Determine the character to use for spaces based on the first character of the URL
        if [[ "${url:0:1}" == "+" ]]; then
            # Replace spaces with plus signs
            formatted_input="${MOVSHWO// /+}"
        elif [[ "${url:0:1}" == "-" ]]; then
            # Replace spaces with hyphens
            formatted_input="${MOVSHWO// /-}"
        else
            # Default to using spaces if no prefix is found
            formatted_input="$MOVSHWO"
        fi
        
        # Remove the prefix character from the URL
        url="${url:1}"
        
        # Output the concatenated URL
        echo "${url}${formatted_input}"
    done < "$file"
}

# Menu for user to choose between shows and movies
echo "Please choose an option:"
echo "1) Use Shows File"
echo "2) Use Movies File"
read -p "Enter your choice (1 or 2): " choice

case $choice in
    1)
        echo "Concatenated URLs for shows:"
        concat_urls "$shows_file"
        ;;
    2)
        echo "Concatenated URLs for movies:"
        concat_urls "$movies_file"
        ;;
    *)
        echo "Invalid choice. Please enter 1 or 2."
        exit 1
        ;;
esac
