# RepoMind Frontend (Vite + React)

## Requirements
- Node.js 18+ (Node.js 20 LTS recommended)
- npm

## Run locally
```bash
npm install
cp .env.example .env
npm run dev
```
On Windows PowerShell, use:
```powershell
Copy-Item .env.example .env
```
Open the local URL printed by Vite, normally http://localhost:5173.

## Backend connection
The frontend expects the Java Spring Boot API at:
`VITE_API_BASE_URL=http://localhost:8080/api`

Expected endpoints:
- `POST /repositories/analyze` with `{ "repository_url": "https://github.com/owner/repo" }`
- `POST /matching/analyze` with `{ "repository_url": "...", "job_description": "..." }`
- `POST /learning/ask` with `{ "repository_url": "...", "question": "..." }`

If the backend is unreachable the UI shows an error message (there is no demo data).

## Test
```bash
npm test
```

## Build
```bash
npm run build
npm run preview
```

## Notes
This is a frontend starter. It does not implement authentication, GitHub OAuth, code execution, static analysis, ML inference, or persistence. Those belong in the backend services. Never execute untrusted repository code on the application server.