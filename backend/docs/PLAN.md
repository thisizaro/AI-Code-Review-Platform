# Backend Plan and Status

Read [PRD.md](PRD.md) for the why. This file is the order of work and what is finished.
Two tracks: **Java** (person A) and **Python** (person B). Frontend cleanup is shared.

## Status at a glance

| Step | State |
|---|---|
| 0 Setup | Done |
| 1 Skeletons | Skipped on purpose: both services were built for real, test first, instead of stubbed |
| 2 Real core | Done |
| 3 AI features | Done. Tried with a real Groq key on a real repo (analyze, match, ask all worked) |
| 4 Integrate and clean up | Done |
| 5 Ship | README done. Real-world check and demo rehearsal still open |

Verified so far: 84 Python tests, 70 Java tests (stub GitHub and Python servers), 19 frontend tests, plus an independent black-box pass (about 170 requests against both real services and a fake GitHub). Everything it found was fixed test first.

## Step 0: Setup
- [x] Contracts agreed (PRD section 6)
- [x] Root `.gitignore` (keeps `.env`, `node_modules`, `target`, `.venv` out of git)
- [x] Folder layout: `backend/java-api`, `backend/python-ai`, `backend/docs`
- [x] Groq key set up (Gemini is optional backup, root README says where each token goes)
- [ ] Pick a demo repo and one job description to test with

## Step 1: Skeletons
- [x] Skipped, see status table

## Step 2: Real core
Java (A)
- [x] URL validation and normalization (accepts `.git`, `/tree/...`; rejects other hosts, `..`, control characters)
- [x] GitHub zipball download, in-memory unzip, file filters and limits (300 files, 200 KB each, 1.5 MB total)
- [x] Cache by `owner/repo@sha`
- [x] Metrics and quality score formula
- [x] Error mapping (400, 404, 405, 413, 415, 429, 502, 504) with a `{message}` body
- [x] CORS, request size limit, strict JSON types

Python (B)
- [x] `/internal/analyze`: AST checks for Python, regex heuristics for other languages
- [x] Security scan: secrets, eval/exec, shell=True, SQL concatenation, weak hashes, disabled TLS verification
- [x] Function counting
- [x] Unit tests

## Step 3: AI features
Java (A)
- [x] Send the bundle to Python for match and ask
- [x] Timeouts and friendly messages when Python is down or slow

Python (B)
- [x] LLM provider layer: Groq and Gemini, fallback, retry on 429, JSON retry, prompt cache
- [x] `/internal/ask`: BM25 retrieval, bounded prompt, sources, secrets redacted
- [x] `/internal/match`: skill extraction, aliases, score, explanation, keyword fallback with required vs "nice to have"
- [x] LLM rewrite of the top finding explanations (titles and locations only, never source code)
- [x] Tried with a real Groq key. The old default model was retired, switched to `openai/gpt-oss-120b`

## Step 4: Integrate and clean up
- [x] Frontend: demo fallbacks removed, URL check loosened, dead top-bar buttons removed, clear error and timeout messages
- [x] Black-box test pass and fixes
- [x] Real run on pallets/click (157 files, about 5 seconds)
- [ ] Test with 2 more real repos (small, too large) and a bad URL
- [ ] Tune score weights: pallets/click scored 50 because its 95 long test functions hit the penalty cap (see Known limits)

## Step 5: Ship
- [x] Root `README.md`: how to run, config table, test commands
- [x] `.env.example` for Python
- [ ] Rehearse the demo with the checklist in PRD section 11; each person explains their half

## Known limits
- The quality score penalizes the raw count of findings, not their density, and flags long test functions. Large, well-tested repositories can score lower than they deserve. Fix idea: score per 1000 lines and skip test files for length and complexity rules.
- Skill matching only recognizes evidence for skills in its built-in vocabulary, so skills like "CLI" or "Git" always show as missing.
- Without an LLM key, Q&A returns the top matching code snippets (secrets redacted) and the ranking can include loosely related files.
- Without an LLM key, skill matching uses keywords only, and treats "nice to have" or "a plus" sentences as preferred.
- Public GitHub repositories only. Unauthenticated GitHub allows 60 requests per hour per IP, so set `GITHUB_TOKEN`.

## Rules of the road
- Never break the public contract without telling the other person.
- If something is behind, cut in this order: LLM explanations on findings, then the Gemini fallback, then non-Python static analysis. Do not cut the three core endpoints.
- Commit small, one branch per track, merge to `main` at each check.
