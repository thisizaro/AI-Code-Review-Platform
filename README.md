# RepoMind

Analyze a public GitHub repository for code quality and security, match it against a job description, and ask questions about the code.

```
React (Vite)  ->  Java Spring Boot API  ->  Python FastAPI AI service  ->  Groq / Gemini
  :5173              :8080                      :8000
                       |
                       +-> GitHub (zipball download)
```

| Folder | What it is | Owner story |
|---|---|---|
| `frontend/repomind-vite-frontend` | React UI | Calls the Java API only |
| `backend/java-api` | Spring Boot 3, Java 21 | Validates URLs, downloads and filters the repo, caches, computes the quality score, orchestrates Python, maps errors |
| `backend/python-ai` | FastAPI | Static analysis, security scan, LLM provider layer (Groq/Gemini), skill matching, code Q&A |
| `backend/docs/PRD.md` | Requirements, API contracts, scoring | Read this first |
| `backend/docs/PLAN.md` | Order of work and what is done | |

## Run it (3 terminals)

Needs Node 18+, Java 21, Python 3.11+. On Debian or Ubuntu you may need `sudo apt install python3-venv` first.

**1. Python AI service**
```bash
cd backend/python-ai
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
cp .env.example .env        # then put your GROQ_API_KEY and/or GEMINI_API_KEY in .env
.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000
```
Without an API key everything still works in a reduced mode (heuristic findings, keyword skill matching, Q&A returns the best matching code snippets). `GET http://127.0.0.1:8000/health` shows whether an LLM is configured.

**2. Java API**
```bash
cd backend/java-api
export GITHUB_TOKEN=...     # optional, raises GitHub's 60 requests/hour limit to 5000
./mvnw spring-boot:run
```

**3. Frontend**
```bash
cd frontend/repomind-vite-frontend
npm install && cp .env.example .env && npm run dev
```
Open http://localhost:5173.

## Configuration

| Variable | Service | Default | Purpose |
|---|---|---|---|
| `LLM_PROVIDER` | Python | `groq` | `groq` or `gemini`, the other is used as fallback if its key is set |
| `GROQ_API_KEY`, `GROQ_MODEL` | Python | none, `llama-3.3-70b-versatile` | Groq free tier |
| `GEMINI_API_KEY`, `GEMINI_MODEL` | Python | none, `gemini-2.0-flash` | Gemini free tier |
| `GITHUB_TOKEN` | Java | none | Optional GitHub token (public repos only are supported) |
| `PYTHON_SERVICE_URL` | Java | `http://localhost:8000` | Where the AI service lives |
| `CORS_ORIGIN` | Java | `http://localhost:5173` | Allowed browser origin |
| `SERVER_PORT` | Java | `8080` | |

Model names change; check the provider's current free models if a call fails. Never commit `.env`.

## Tests

```bash
cd backend/python-ai && .venv/bin/python -m pytest          # 84 tests
cd backend/java-api && ./mvnw test                          # 70 tests (stub GitHub and Python servers, no network)
cd frontend/repomind-vite-frontend && npm test              # 19 tests
```

## Limits and safety

- Public GitHub repositories only. Repository code is never executed, only read as text.
- Per repository: 300 files, 200 KB per file, 1.5 MB total. When a limit is hit the response says `metrics.truncated: true`.
- Only `https://github.com/{owner}/{repo}` URLs are accepted, which also blocks SSRF.
- Findings and scores are assistive signals, not verdicts.
