# Local setup

Install and start Docker Desktop on macOS or Windows, or Docker Engine on Linux.

On macOS and Linux, run `scripts/start.sh` to build and start the app at `http://localhost:8000`; run `scripts/stop.sh` to stop it. On Windows PowerShell, run `.\scripts\start.ps1` and `.\scripts\stop.ps1`. Set `PORT` before starting to use a different local port.

The app at `/` is the statically exported Kanban board. `GET /api/health` is the backend health endpoint, and `/api/board` reads/updates the authenticated user's board. Docker stores the database in the persistent `pm-data` volume at `/data/kanban.sqlite3`.

For local development and tests, first build the frontend from `frontend/` with `npm ci && npm run build`. Then, from `backend/`, use `uv run --group dev pytest` to run backend tests or `uv run uvicorn app.main:app --reload` to serve the built app and API. The frontend end-to-end test command builds the export and starts FastAPI automatically.

Sign in with `user` / `password`. The local MVP uses a signed, HTTP-only session cookie; its signing key is generated when the backend starts, so restarting the backend requires signing in again. This is a local demo mechanism, not production authentication.

For AI features, set `OPENROUTER_API_KEY` in the project-root `.env` file. The start scripts pass that file to the backend container when present; no key is required to run the non-AI features. The default is `xingchen-agi/xing4.0-29b-a4b:free`. Requests use OpenRouter's model fallback routing with only free models that support structured outputs, so rate-limited providers can fall back without selecting a paid model. Set `OPENROUTER_MODEL` to one of the configured free model IDs to change the first choice.

If chat still reports HTTP 429 after model fallbacks, OpenRouter may have rate-limited the account or all free providers. Check usage and limits in your OpenRouter account or wait and retry.

To make an explicit live connectivity request from `backend/`, run `uv run python -m app.check_openrouter`. This sends a `2 + 2` prompt to OpenRouter; the automated tests use mocked HTTP and do not require a key or network access.
