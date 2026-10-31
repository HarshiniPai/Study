# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Project Management MVP ("Kanban Studio"): a Next.js Kanban board with sign-in and an AI chat sidebar that can create, edit, and move cards. A FastAPI backend serves the frontend and API from one origin, and everything is packaged in one Docker container. `AGENTS.md` (root), `frontend/AGENTS.md`, and `backend/AGENTS.md` hold the requirements and per-area conventions. Read them along with `docs/PLAN.md`, the phased plan with checklists. Check items off and record deviations in `docs/PLAN.md` as you complete work.

## Commands

Run the full app in Docker at http://localhost:8000 (set `PORT` to change it):
- `scripts/start.sh` / `scripts/stop.sh` (Windows: `scripts/start.ps1` / `scripts/stop.ps1`). The container is `pm-mvp`, and the SQLite data lives in the `pm-data` volume.

Frontend (run from `frontend/`):
- `npm run dev`, `npm run build` (static export to `frontend/out/`), `npm run lint`
- `npm run test:unit`: Vitest (jsdom). Single test: `npx vitest run src/components/KanbanBoard.test.tsx -t "test name"`
- `npm run test:e2e`: Playwright. It builds the export and then runs `scripts/e2e-server.sh`, which starts FastAPI on 127.0.0.1:8000 with a throwaway temp DB. It needs `uv` and the backend deps, and port 8000 must be free (stop the Docker container first).
- `npm run test:all`: unit tests, then e2e tests

Backend (run from `backend/`, managed with `uv`):
- `uv run --group dev pytest`. Single test: `uv run --group dev pytest tests/test_main.py::test_name`
- `uv run uvicorn app.main:app --reload` serves the API plus `../frontend/out` (build the frontend first)
- `uv run python -m app.check_openrouter` makes a live OpenRouter call (needs a key). The regular tests mock HTTP and need no key or network.

## Architecture

- **Single origin.** `backend/app/main.py` exposes `/api/*` routes and serves the statically exported Next.js app (`frontend/out`) at `/`. The frontend calls relative `/api/...` URLs, so in development it expects the FastAPI server rather than `next dev` for any backend-dependent behavior.
- **Auth.** Credentials are hardcoded as `user` / `password` (`/api/auth/login|logout|me`) and stored in a signed HTTP-only session cookie. The signing key is generated at startup, so a backend restart logs everyone out. `AuthGate.tsx` gates the board on `/api/auth/me`.
- **Board persistence.** `app/database.py` owns the SQLite schema (versioned with `PRAGMA user_version`, foreign keys on), seeding of `mvp-user` and their 5-column board, and the board read/write queries. `GET/PUT /api/board` read or replace the whole board for the session's user. The DB path comes from `DATABASE_PATH`. The schema rationale is in `docs/DATABASE.md` and `docs/database-schema.json`. Invariants: exactly 5 fixed columns (they can be renamed but never added, removed, or reordered), and card order is set by `position`.
- **Frontend state.** `KanbanBoard.tsx` keeps an optimistic in-memory board, PUTs the full board on each change, and rolls back if the save fails. Shared types and pure board operations live in `src/lib/kanban.ts`. Drag-and-drop uses `@dnd-kit`.
- **AI flow.** `AIChatSidebar.tsx` sends a POST to `/api/ai/chat` with the question and history. `app/ai.py` sends OpenRouter the current board JSON with a strict JSON-schema `response_format` (`answer` plus a list of `create_card` / `edit_card` / `move_card` actions), validates the reply with Pydantic, and applies the actions to a copy of the board. The route saves the board if it changed and returns `{answer, board, board_updated}`. Errors map to HTTP 503 (missing key or config) and 502 (bad AI output or invalid update). `app/openrouter.py` only allows a fixed list of free models and uses OpenRouter's fallback routing across them. `OPENROUTER_MODEL` picks which one is tried first.
- **Secrets.** `OPENROUTER_API_KEY` is read on the backend only, from the environment or the root `.env` (the start scripts pass it with `--env-file`). Never expose it to the frontend or log it.

## Conventions (from AGENTS.md)

- Keep it simple: no over-engineering, no extra features, no unnecessary defensive code. Keep the README minimal. Never use emojis.
- When debugging, find and prove the root cause with evidence before fixing.
- Use the palette in the existing Tailwind CSS variables: accent yellow `#ecad0a`, blue primary `#209dd7`, purple secondary `#753991` (submit/important actions), dark navy `#032147` (headings), gray `#888888` (supporting text).
- Keep accessible labels and roles on controls, since the tests query by them.
- Keep tests that need Docker, network access, or a real OpenRouter key separate from the regular test suite.
