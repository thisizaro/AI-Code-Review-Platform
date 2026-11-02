# RepoMind Backend: Product Requirements (v0.1 draft)

Status: draft for the team to review. Nothing is built yet.

## 1. Purpose

The React frontend (`frontend/repomind-vite-frontend`) is finished and already calls three endpoints. This backend makes those endpoints real: given a public GitHub repository URL, it analyzes code quality and security, matches the repo against a job description, and answers questions about the code.

## 2. Scope

In scope (MVP):
- Public GitHub repositories only.
- Three features: code analysis, job description matching, project learning (Q&A).
- Free LLM API (Groq primary, Gemini fallback), keys read from environment variables.
- No database. In-memory caching only.

Out of scope (do not build):
- Login, GitHub OAuth, private repositories.
- Running or executing any code from a repository.
- Persistent history, user accounts, payments.
- Vector databases or model training.

## 3. Architecture

```
Browser (React, :5173)
   |  public API, snake_case JSON (already fixed by the frontend)
   v
Java Spring Boot  :8080   "API + repo ingestion + scoring"
   |  internal REST, localhost only
   v
Python FastAPI    :8000   "analysis + AI"
   |
   +--> Groq API (primary) / Gemini API (fallback)
Java also calls --> GitHub REST API (zipball download)
```

Why two services: it splits the work into one Java task and one Python task, so two people can each own, build and explain their half. The Python service never talks to the browser or GitHub. The Java service never talks to an LLM.

## 4. Ownership split

| | Java (Spring Boot) | Python (FastAPI) |
|---|---|---|
| Role | The "front desk": validates requests, fetches the repo, scores, returns the response | The "brain": finds issues, extracts skills, answers questions |
| One-line pitch | "Turns a GitHub URL into a clean bundle of files, and turns findings into a score" | "Reads the code with static checks and an LLM, and returns findings and answers" |
| Owns | Public REST API, CORS, URL validation, GitHub download, file filtering and limits, caching, quality score and metrics, error format, calling Python | Static analysis, secret and risk scanning, LLM provider layer (Groq/Gemini), skill extraction and JD matching, retrieval and Q&A |
| Key libraries | Spring Web, Validation, WebClient or RestClient, Caffeine cache | FastAPI, Pydantic, httpx, rank_bm25, `ast` from stdlib |
| Folder | `backend/java-api/` | `backend/python-ai/` |

## 5. Functional requirements

### Java service
- J1. Expose `POST /api/repositories/analyze`, `POST /api/matching/analyze`, `POST /api/learning/ask`, and `GET /api/health`.
- J2. Validate `repository_url` as `https://github.com/{owner}/{repo}` (also accept a trailing `/`, `.git`, or `/tree/...` and normalize it). Reject anything else with HTTP 400. This also prevents SSRF.
- J3. Download the repo as a zipball through the GitHub API (one request, avoids the 60 requests per hour unauthenticated limit) and read it in memory. Use `GITHUB_TOKEN` if set.
- J4. Filter files: keep text source and config files only. Skip binaries, images, lock files, `node_modules`, `vendor`, `dist`, `build`, `.git`. Limits: max 300 files, max 200 KB per file, max 1.5 MB total. Truncate and report if exceeded.
- J5. Cache the filtered bundle by `owner/repo@sha` (Caffeine, 30 min TTL) so repeated calls do not re-download.
- J6. Compute metrics: `files_analyzed`, `test_files` (path contains `test`, `spec`, `__tests__`), language breakdown.
- J7. Compute `quality_score` from the findings Python returns (formula in section 7).
- J8. Call Python with a 60 second timeout. Map failures to the error format below.
- J9. Return errors as `{ "message": "..." }` (the frontend reads `message`) with proper status codes: 400 bad input, 404 repo not found, 429 rate limited, 502 AI service failed, 504 timeout.

### Python service
- P1. `POST /internal/analyze`: static analysis. For Python files use `ast` (function length, nesting depth, branch count, bare `except`, `eval`/`exec`, mutable default args). For other languages use regex and line heuristics (very long functions or files, TODO/FIXME density, empty catch blocks).
- P2. Security scan in the same endpoint: regex for hardcoded secrets (API keys, tokens, private keys, passwords), SQL built by string concatenation, `eval`, `shell=True`, `Runtime.exec`, disabled TLS verification, weak hashes (MD5, SHA1). Each finding has severity, file, line, explanation.
- P3. LLM pass on the top 5 riskiest files to rewrite finding explanations in plain language. If the LLM fails, return the heuristic findings anyway.
- P4. `POST /internal/match`: LLM extracts required and preferred skills from the job description as JSON. Repo evidence comes from manifests (`pom.xml`, `package.json`, `requirements.txt`, `Dockerfile`, CI files), imports, and file extensions. Compute the score (section 7) and ask the LLM for a short explanation.
- P5. `POST /internal/ask`: retrieve the most relevant files with BM25 (query vs file path plus content, chunked), send the top chunks to the LLM, return the answer and the file paths used as `sources`. The prompt must say: answer only from the provided code, say "not found in the provided files" otherwise.
- P6. LLM provider layer: one interface, two implementations (Groq, Gemini), selected by `LLM_PROVIDER`. On HTTP 429 or error from the primary, retry once, then fall back to the other provider if its key exists.
- P7. `GET /health` reports which provider and model are configured (never the key).

## 6. API contracts

### Public (Java, consumed by the frontend; do not change field names)

`POST /api/repositories/analyze`
```json
{ "repository_url": "https://github.com/owner/repo" }
```
returns
```json
{
  "quality_score": 78,
  "issues": [{ "severity": "Medium", "title": "...", "file": "src/a.py", "line": 12, "detail": "..." }],
  "security_findings": [{ "severity": "High", "title": "...", "file": "...", "line": 3, "detail": "..." }],
  "metrics": { "files_analyzed": 42, "functions_analyzed": 118, "test_files": 9 }
}
```
Severity is one of `High`, `Medium`, `Low`, `Info`.

`POST /api/matching/analyze`
```json
{ "repository_url": "...", "job_description": "..." }
```
returns `{ "match_score": 76, "matched_skills": ["Python"], "missing_skills": ["AWS"], "explanation": "..." }`

`POST /api/learning/ask`
```json
{ "repository_url": "...", "question": "..." }
```
returns `{ "answer": "...", "sources": ["src/main.py", "README.md"] }`

### Internal (Java to Python, JSON)

All three take the same bundle (Java builds it, Python never fetches anything):
```json
{
  "repo": {
    "url": "https://github.com/owner/repo", "owner": "owner", "name": "repo", "sha": "abc123",
    "files": [{ "path": "src/a.py", "language": "python", "size": 1204, "content": "..." }]
  }
}
```
- `POST /internal/analyze` returns `{ "issues": [...], "security_findings": [...], "functions_analyzed": 118 }`
- `POST /internal/match` adds `"job_description"`, returns `{ "match_score": 76, "matched_skills": [], "missing_skills": [], "explanation": "..." }`
- `POST /internal/ask` adds `"question"`, returns `{ "answer": "...", "sources": [] }`

Errors from Python: `{ "detail": "..." }` with 4xx or 5xx. Java translates them.

## 7. Scoring (keep simple so it can be explained in one sentence)

Quality score (computed in Java):
```
penalty = min(60, 6*high + 3*medium + 1*low)        // from issues + security_findings
test_bonus = min(10, 50 * test_files / max(1, files_analyzed))       // full 10 points at 20% test files
quality_score = clamp(round(100 - penalty + test_bonus), 0, 100)
```
Weights are a starting point and can be tuned after trying 3 real repos.

Match score (computed in Python):
```
match_score = round(100 * (2*matched_required + 1*matched_preferred)
                        / (2*total_required + 1*total_preferred))
```
Skills are lowercased and alias-normalized (`js` = `javascript`, `postgres` = `postgresql`) before comparing. If the job description yields zero skills, return 400 "No skills found in job description".

## 8. LLM requirements (free tier)

- Providers: Groq (OpenAI-compatible endpoint, Llama models) and Google Gemini (flash model). Model names change often, so they live in env, not code. Check each provider's current free model list when setting up.
- Free tiers have tight requests per minute and tokens per minute limits. Design for it: keep prompts under about 4,000 tokens, send only the top retrieved chunks, never send whole repos, cache LLM answers by hash of the prompt, retry once with a short backoff on 429.
- Ask for JSON output where structure is needed and validate it with Pydantic; on invalid JSON, retry once, then fall back to heuristics.
- The app must still work, with reduced quality, if no key is set (heuristic findings, keyword-only skill matching, and Q&A that returns the top matching file snippets).

## 9. Configuration

| Variable | Service | Purpose |
|---|---|---|
| `LLM_PROVIDER` | Python | `groq` or `gemini` (default `groq`) |
| `GROQ_API_KEY`, `GROQ_MODEL` | Python | Groq credentials and model |
| `GEMINI_API_KEY`, `GEMINI_MODEL` | Python | Gemini credentials and model |
| `GITHUB_TOKEN` | Java | Optional, raises GitHub rate limit |
| `PYTHON_SERVICE_URL` | Java | Default `http://localhost:8000` |
| `CORS_ORIGIN` | Java | Default `http://localhost:5173` |

Keys live in `.env` files (git-ignored) with a committed `.env.example` listing names only. Never log keys or full file contents.

## 10. Non-functional and security

- Never execute repository code. Analysis is read-only text processing.
- Only `github.com` URLs are fetched, and only by the Java service.
- Python binds to `127.0.0.1` so only Java can reach it.
- Response time: analysis under 60 seconds for a repo within the limits; Q&A under 15 seconds.
- Logging: request id, repo, duration, provider used. No secrets, no source code.
- Findings are assistive signals. Wording in the UI already says this; keep the API honest about uncertainty.

## 11. Acceptance criteria (demo day checklist)

1. With both services running, pasting a public repo URL on Code Analysis shows a real score, real findings with file names, and no "DEMO DATA" tag.
2. Job Matching returns matched and missing skills that make sense for a pasted job description.
3. Project Learning answers "Where are the API routes defined?" with correct file references.
4. A bad URL, a nonexistent repo, and a missing API key each show a clear error message, not a crash.
5. A fresh clone can be run by following the README in under 10 minutes.
6. Each teammate can explain their service using section 4 and show where each endpoint lives.

## 12. Risks

| Risk | Mitigation |
|---|---|
| Free LLM rate limits during the demo | Fallback provider, prompt caching, heuristic fallback, pre-warm the demo repo |
| Huge repos | Hard file and size limits (J4), report truncation in the response |
| GitHub rate limit | Zipball approach, optional token, cache by sha |
| Two people blocked on each other | Freeze the contracts in section 6 first, build stubs, work in parallel |

## 13. Open questions

- Which repo(s) will be used in the demo? We should test on those early.
- Does the course or evaluator require a specific Java build tool (Maven vs Gradle)? Default: Maven.
- Is Groq or Gemini the one the team already has a key for? Default: Groq primary.
