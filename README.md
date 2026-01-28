# 🎬 Dvora

Dvora is a terminal-based Go application that helps you quickly check whether a **movie or TV show** exists across multiple streaming and index sites.

---

## 🚀 Installation & Usage (Start Here)

### 1️⃣ Clone the repository

```
git clone https://github.com/master-sauce/dvora.git
cd dvora
```

---

### 2️⃣ Build the project

```
go build -o dvora
```

---

### 3️⃣ Run

```
./dvora
```

---

### 4️⃣ Follow the prompts

* Enter the movie or show name
* Choose:

  * `1` → Shows
  * `2` → Movies

Dvora will:

1. Check regular sites
2. Check API-based sites
3. Output manual search URLs

---

## ✨ Features

* 🔍 Search for **movies or TV shows** by name
* 📄 Supports multiple site lists via text files
* 🌐 HTML scraping with smart link matching
* ⚙️ API-based site support (JSON search endpoints)
* 🧠 Avoids false positives using "no results" detection
* 🖥️ Clean terminal UI with ASCII welcome screen
* 🧾 Generates manual URLs when automation isn’t possible

---

## 📁 Project Files

| File                | Description                              |
| ------------------- | ---------------------------------------- |
| `main.go`           | Main application source code             |
| `shows.txt`         | List of TV show search URLs              |
| `movies.txt`        | List of movie search URLs                |
| `api_sites.txt`     | Base URLs for API-based sites (optional) |
| `manual_checks.txt` | Sites that must be checked manually      |

---

## 🧩 How Site Files Work

Dvora does **not guess** how a site searches. Instead, *you teach it* by copying how the site’s search URL works and adding it to a file.

Each line in the site files represents a **base search URL**.

### 🔎 How to Add a New Site (Step-by-Step)

Follow these steps to add **any new streaming or index site**.

---

### 1️⃣ Find the site’s search URL

1. Open the website in your browser
2. Search for **any movie or show** (for example: `test`)
3. Look at the browser address bar

You’ll usually see something like:

```
https://example.com/search?q=test
https://example.com/search/test
https://example.com/?s=test
```

Everything **before** the search word is what Dvora needs.

---

### 2️⃣ Identify the search parameter

Common patterns:

| Pattern    | Meaning           |
| ---------- | ----------------- |
| `?q=`      | Query parameter   |
| `?s=`      | Search parameter  |
| `/search/` | Path-based search |

Examples:

```
https://site.com/search?q=movie
https://site.com/?s=movie
https://site.com/search/movie
```

---

### 3️⃣ Decide how spaces are handled

Now check how the site handles **spaces** in multi-word titles:

Search for something like:

```
The Dark Knight
```

If the URL becomes:

* `The+Dark+Knight` → use `+` **(most common)**
* `The-Dark-Knight` → use `-`
* `The Dark Knight` → use **no prefix**

---

### 4️⃣ Add the site to the correct file

Choose the file:

* `shows.txt` → TV shows
* `movies.txt` → Movies
* `manual_checks.txt` → Sites that block scraping

Then add the base search URL **with the correct prefix**.

---

### Prefix Rules

You can control how the search term is formatted using prefixes:

* `+` → spaces replaced with `+`
* `-` → spaces replaced with `-`
* no prefix → original input preserved

### 🧪 Full Example

Let’s say a site searches like this:

```
https://movies.example/search?q=The+Dark+Knight
```

You would add:

```
+https://movies.example/search?q=
```

Another site searches like:

```
https://shows.example/search/The-Dark-Knight
```

Add:

```
-https://shows.example/search/
```

If a site keeps spaces:

```
https://weird.example/find/The Dark Knight
```

Add:

```
https://weird.example/find/
```

---

### Example File Content

```
+https://example.com/search?q=
-https://example2.com/search/
https://example3.com/find/
```

Searching for:

```
The Dark Knight
```

Will produce:

```
https://example.com/search?q=The+Dark+Knight
https://example2.com/search/The-Dark-Knight
https://example3.com/find/The Dark Knight
```

---

## 🌐 API Site Support

Dvora supports sites that expose a `/searching?q=` JSON endpoint.

The app:

* Sends a proper User-Agent
* Parses JSON responses
* Matches titles intelligently
* Outputs a **real search URL** if found

`api_sites.txt` is optional — if missing or empty, API checks are skipped automatically.

---

## 🧪 Manual Checks

Some sites block scraping or require JavaScript.

Dvora prints ready-to-click URLs from `manual_checks.txt` so you can quickly verify results in your browser.

---

## ⚠️ Notes & Disclaimer

* This tool is for **educational and research purposes only**
* Respect website terms of service
* Avoid excessive requests
* Some sites may block requests regardless of User-Agent

---

## 🛠️ Requirements

* Go **1.20+** recommended
* Internet connection

Dependencies:

* `golang.org/x/net/html`

---

## 💡 Future Ideas

* Parallel site checks
* Result ranking
* Export results to file
* Headless browser fallback
* Configurable timeouts

---

## ❤️ Author

Built with curiosity, caffeine, and Go.

Happy hunting 🎥
