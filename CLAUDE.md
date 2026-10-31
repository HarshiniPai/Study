# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Project Management MVP ("Kanban Studio"): a Next.js Kanban board with sign-in and an AI chat sidebar that can create, edit, and move cards. A Java Spring Boot backend serves the frontend and API from one origin, and everything is packaged in one Docker container. `AGENTS.md` (root), `frontend/AGENTS.md`, and `backend/AGENTS.md` hold the requirements and per-area conventions. Read them along with `docs/PLAN.md`, the phased plan with checklists. Check items off and record deviations in `docs/PLAN.md` as you complete work. `docs/CODE_REVIEW.md` tracks open review findings.

## Commands

Run the full app in Docker at http://localhost:8000 (set `PORT` to change it):
- `scripts/start.sh` / `scripts/stop.sh` (Windows: `scripts/start.ps1` / `scripts/stop.ps1`). The container is `pm-mvp`, and the SQLite data lives in the `pm-data` volume.

Frontend (run from `frontend/`):
- `npm run dev`, `npm run build` (static export to `frontend/out/`), `npm run lint`
- `npm run test:unit`: Vitest (jsdom). Single test: `npx vitest run src/components/KanbanBoard.test.tsx -t "test name"`
- `npm run test:e2e`: Playwright. It builds the export and then runs `scripts/e2e-server.sh`, which packages and starts the Spring Boot jar on 127.0.0.1:8000 with a throwaway temp DB. It needs JDK 25, and port 8000 must be free (stop the Docker container first).
- `npm run test:all`: unit tests, then e2e tests

Backend (run from `backend/`, Java 25 + Maven wrapper):
- `./mvnw test`. Single test: `./mvnw test -Dtest=ApiControllerTest#loginLogoutFlow`. These tests don't need the frontend build.
- `./mvnw spring-boot:run` serves the API plus `../frontend/out` (build the frontend first)
- `java -jar target/app.jar --spring.main.web-application-type=none --app.check-openrouter=true` (after `./mvnw -DskipTests package`) makes a live OpenRouter call and needs a key. The regular tests mock HTTP and need no key or network.

## Architecture

Backend code is in `backend/src/main/java/com/kanbanstudio/`.

- **Single origin.** `web/ApiController.java` exposes `/api/*` routes. Spring serves the statically exported Next.js app (`app.static-dir`, default `../frontend/out`) at `/`. The frontend calls relative `/api/...` URLs, so in development it expects the backend server rather than `next dev` for any backend-dependent behavior. Errors are returned as `{"detail": "..."}` (`ApiExceptionHandler`), and the chat UI shows `detail`. Malformed request bodies return 422.
- **Auth.** Credentials are hardcoded as `user` / `password` (`/api/auth/login|logout|me`), and the signed-in user is kept in the servlet session (in memory). A backend restart logs everyone out. Login replaces any existing session. `AuthGate.tsx` gates the board on `/api/auth/me`.
- **Board persistence.** `db/BoardRepository.java` owns the SQLite schema (versioned with `PRAGMA user_version`; foreign keys and IMMEDIATE transactions are set in `DatabaseConfig`), seeding of `mvp-user` and their 5-column board, and the board read/write queries. `GET/PUT /api/board` read or replace the whole board for the session's user. The DB path comes from `DATABASE_PATH`. The schema rationale is in `docs/DATABASE.md` and `docs/database-schema.json`. Invariants: exactly 5 fixed columns (they can be renamed but never added, removed, or reordered), and card order is set by `position`.
- **JSON contract.** The records in `model/` define the field names the frontend depends on: `cardIds`, `board_updated`, and the AI action fields `card_id` / `column_id`. Jackson 3 (`tools.jackson.*`) is the JSON library.
- **Frontend state.** `KanbanBoard.tsx` keeps an optimistic in-memory board, PUTs the full board on each change, and rolls back if the save fails. Shared types and pure board operations live in `src/lib/kanban.ts`. Drag-and-drop uses `@dnd-kit`.
- **AI flow.**
  1. `AIChatSidebar.tsx` sends a POST to `/api/ai/chat` with the question and history.
  2. `ai/AiService.java` sends OpenRouter the current board JSON with a strict JSON-schema `response_format` (`answer` plus a list of `create_card` / `edit_card` / `move_card` actions). It parses the reply strictly (unknown fields are rejected) and returns the validated actions.
  3. `ApiController` re-reads the board after the AI call and applies the actions to it with `ai/BoardActions.java`. This keeps edits the user saved while the AI was responding. If an action no longer fits the board, it returns 409 and saves nothing.
  4. The route saves the board if it changed and returns `{answer, board, board_updated}`.

  Errors map to HTTP 503 (missing key or config) and 502 (provider failure or bad AI output). `ai/OpenRouterClient.java` only allows a fixed list of free models and uses OpenRouter's fallback routing across them. `OPENROUTER_MODEL` picks which one is tried first.
- **Secrets.** `OPENROUTER_API_KEY` is read on the backend only, from the environment or the root `.env`, which `application.properties` imports. The start scripts pass `.env` to the container with `--env-file`. Never expose the key to the frontend or log it.

## Conventions (from AGENTS.md)

- Keep it simple: no over-engineering, no extra features, no unnecessary defensive code. Keep the README minimal. Never use emojis.
- When debugging, find and prove the root cause with evidence before fixing.
- Use the palette in the existing Tailwind CSS variables: accent yellow `#ecad0a`, blue primary `#209dd7`, purple secondary `#753991` (submit/important actions), dark navy `#032147` (headings), gray `#888888` (supporting text).
- Keep accessible labels and roles on controls, since the tests query by them.
- Keep tests that need Docker, network access, or a real OpenRouter key separate from the regular test suite.
