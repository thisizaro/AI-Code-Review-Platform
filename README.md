# RepoMind

Paste a public GitHub repository URL and RepoMind will:
1. **Analyze it** for code quality and security problems and give a quality score.
2. **Match it to a job description** and show which required skills the repository evidences.
3. **Answer questions about the code** ("Where are the API routes defined?") with file references.

This README is the single guide for setting up and running the whole app. If you are using an AI chat to help, paste this whole file into it.

## How it fits together

```
Browser (React + Vite)  ->  Java API (Spring Boot)  ->  Python AI service (FastAPI)  ->  Groq / Gemini
     port 5173                  port 8080                    port 8000
                                   |
                                   +-> GitHub (downloads the repo as a zip)
```

| Folder | What it does |
|---|---|
| `frontend/repomind-vite-frontend` | The web UI. Talks only to the Java API. |
| `backend/java-api` | Checks the URL, downloads and filters the repo, caches it, computes the quality score, calls the Python service, turns failures into clear errors. |
| `backend/python-ai` | Finds code issues and security problems, talks to the LLM (Groq, with Gemini as backup), matches skills, answers code questions. |
| `backend/docs` | `PRD.md` (requirements and API contracts) and `PLAN.md` (what is done and what is left). |

## 1. What you need installed

| Tool | Version | Check with |
|---|---|---|
| Java | 21 or newer | `java -version` |
| Node.js | 18 or newer | `node -v` |
| Python | 3.11 or newer | `python3 --version` |

On Debian or Ubuntu (including WSL), Python also needs `sudo apt install python3-venv`. An internet connection is needed the first time (downloads Maven and dependencies).

## 2. Get your tokens and where each one goes

You need **one** token to start (Groq). The other two are optional.

| Token | Needed? | How to get it | Where to put it |
|---|---|---|---|
| **Groq API key** | Yes (for AI answers) | Sign in at https://console.groq.com, open **API Keys**, click **Create API Key**, copy it (starts with `gsk_`). It is free. | `backend/python-ai/.env`, line `GROQ_API_KEY=...` |
| **Gemini API key** | No, it is the backup if Groq fails | Go to https://aistudio.google.com/apikey and click **Create API key**. | `backend/python-ai/.env`, line `GEMINI_API_KEY=...` |
| **GitHub token** | Only if you see "GitHub rate limit exceeded" | GitHub, **Settings**, **Developer settings**, **Personal access tokens**, **Tokens (classic)**, **Generate new token**. Tick **no scopes** (public repos only). Copy it (starts with `ghp_`). | Not a file. Set it in the terminal that runs the Java API (see step 4). |

Without a Groq key the app still runs in a reduced mode: findings use the built-in rules only, skill matching uses keywords only, and Q&A shows the best matching code snippets instead of a written answer.

Create the Python settings file once:
```bash
cd backend/python-ai
cp .env.example .env
```
Then open `backend/python-ai/.env` in an editor and paste your key after the `=` sign, with no quotes and no spaces:
```
LLM_PROVIDER=groq
GROQ_API_KEY=gsk_your_key_here
GROQ_MODEL=openai/gpt-oss-120b
GEMINI_API_KEY=
GEMINI_MODEL=gemini-2.0-flash
```
`.env` is git-ignored, so it never gets committed. Never share it or paste a key into code.

## 3. One-time setup

```bash
# Python service: create the environment and install packages
cd backend/python-ai
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt        # Windows: .venv\Scripts\pip install -r requirements.txt

# Frontend: install packages and create its settings file
cd ../../frontend/repomind-vite-frontend
npm install
cp .env.example .env                             # Windows PowerShell: Copy-Item .env.example .env
```
The Java API needs no separate install. Its wrapper (`./mvnw`) downloads what it needs on first run.

## 4. Run it (three terminals, all from the project root)

**Terminal 1: Python AI service**
```bash
cd backend/python-ai
.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000        # Windows: .venv\Scripts\uvicorn ...
```

**Terminal 2: Java API**
```bash
cd backend/java-api
./mvnw spring-boot:run                                              # Windows: mvnw.cmd spring-boot:run
```
If you need the GitHub token, set it first in this same terminal:
```bash
export GITHUB_TOKEN=ghp_your_token_here                             # Windows PowerShell: $env:GITHUB_TOKEN="ghp_..."
```

**Terminal 3: Frontend**
```bash
cd frontend/repomind-vite-frontend
npm run dev
```

Stop any of them with `Ctrl+C`.

## 5. Check that it works

With all three running:
```bash
curl localhost:8000/health        # expect: "llm_configured":true
curl localhost:8080/api/health    # expect: {"status":"ok"}
```
If `llm_configured` is `false`, the key was not read. Check the file is named exactly `.env`, sits in `backend/python-ai`, and that you restarted the Python service after editing it.

Then open http://localhost:5173 and try a public repository, for example `https://github.com/pallets/click`:
1. **Code Analysis**: paste the URL and click **Run analysis**. You should get a score, findings with file names and line numbers, and metrics.
2. **Job Matching**: paste the URL and a real job description. You should get a match score with skills found and not found.
3. **Project Learning**: paste the URL and ask "How does the main workflow work?" You should get an answer with source files listed.

## 6. If something goes wrong

| What you see | Cause and fix |
|---|---|
| "Cannot reach the API. Make sure the backend is running." | The Java API (port 8080) is not running. Start Terminal 2. |
| "The AI service is not reachable." | The Python service (port 8000) is not running. Start Terminal 1. |
| "GitHub rate limit exceeded." | GitHub allows only 60 anonymous requests per hour per IP address. Set `GITHUB_TOKEN` (step 2 and 4) and restart the Java API. |
| "Repository not found." | The URL is wrong or the repository is private. Only public repositories are supported. |
| Answers look like raw code snippets, or findings keep the same generic text | The LLM call failed and the app fell back. Look at the Python terminal for a line like `llm provider=groq rejected request: HTTP 404`. A 404 almost always means the model name is retired. List current models with the command below, then put one in `GROQ_MODEL` in `.env` and restart Python. A 401 means the key is wrong. A 429 means you hit the free tier limit, wait a minute. |
| `Address already in use` | An old copy is still running. Stop it, or find it with `ss -ltnp | grep 8080`. |
| Page says only part of the repository was analyzed | Expected for big repositories (see limits below). |

List the models your Groq key can use (the key is read from `.env` and not printed):
```bash
cd backend/python-ai
.venv/bin/python -c "import httpx,dotenv;k=dotenv.dotenv_values('.env')['GROQ_API_KEY'];print('\n'.join(sorted(m['id'] for m in httpx.get('https://api.groq.com/openai/v1/models',headers={'Authorization':'Bearer '+k}).json()['data'])))"
```
Free model names change over time. As of the last check, `openai/gpt-oss-120b`, `openai/gpt-oss-20b` and `qwen/qwen3.8-27b` worked. The Gemini model name in `.env.example` has not been verified, so check it at https://ai.google.dev/gemini-api/docs/models if you rely on Gemini.

## 7. All settings

| Variable | Where | Default | Meaning |
|---|---|---|---|
| `LLM_PROVIDER` | `backend/python-ai/.env` | `groq` | `groq` or `gemini`. The other one is used as a backup if its key is set. |
| `GROQ_API_KEY`, `GROQ_MODEL` | `backend/python-ai/.env` | none, `openai/gpt-oss-120b` | Groq credentials and model. |
| `GEMINI_API_KEY`, `GEMINI_MODEL` | `backend/python-ai/.env` | none, `gemini-2.0-flash` | Gemini credentials and model. |
| `GITHUB_TOKEN` | terminal that runs Java | none | Raises GitHub's limit from 60 to 5000 requests per hour. |
| `PYTHON_SERVICE_URL` | terminal that runs Java | `http://localhost:8000` | Where the Python service lives. |
| `CORS_ORIGIN` | terminal that runs Java | `http://localhost:5173` | Browser address allowed to call the API. |
| `SERVER_PORT` | terminal that runs Java | `8080` | Java API port. |
| `VITE_API_BASE_URL` | `frontend/repomind-vite-frontend/.env` | `http://localhost:8080/api` | Where the frontend finds the API. |

## 8. Limits and safety

- Public GitHub repositories only. Code from a repository is never executed, only read as text.
- Per repository: up to 300 files, 200 KB per file, 1.5 MB total. Beyond that the result is marked partial.
- Only `https://github.com/owner/repo` URLs are accepted (also `.git` and `/tree/...` forms).
- The LLM never receives your source code for the findings step (only titles and file locations). For Q&A it receives the most relevant snippets, with anything that looks like a secret replaced by `[REDACTED]`.
- Scores and findings are assistive signals, not verdicts.

## 9. Run the tests

```bash
cd backend/python-ai && .venv/bin/python -m pytest              # 84 tests
cd backend/java-api && ./mvnw test                              # 70 tests, no internet needed
cd frontend/repomind-vite-frontend && npm test                  # 19 tests
```

## 10. More documentation

- `backend/docs/PRD.md`: requirements, the exact API request and response shapes, scoring formulas.
- `backend/docs/PLAN.md`: what is finished and what is still open.
