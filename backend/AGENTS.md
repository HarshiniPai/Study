# Backend Guide

The backend is a Python FastAPI application. The MVP backend and its tests live in `app/` and `tests/`, respectively. Run backend commands from `backend/`.

Use `uv` to manage dependencies and commands. `pyproject.toml` defines runtime dependencies and the development test dependencies. Add focused pytest coverage for API and backend behavior; use FastAPI's `TestClient` for route tests.

FastAPI exposes API routes under `/api/` and serves the statically exported Next.js app from `frontend/out` at `/`. SQLite schema, initialization, and board queries live in `app/database.py`; request/response types live in `app/schemas.py`. The local MVP sign-in is handled by backend routes and a signed session cookie. Keep browser-facing assets and API routes on the same origin. Do not put secret keys in frontend code or responses; load private configuration only on the backend.

OpenRouter request handling lives in `app/openrouter.py`. The API key is loaded from the process environment or the project-root `.env` file and must never be logged or returned to the frontend. Routine tests use an HTTP mock; `uv run python -m app.check_openrouter` is an explicit live connectivity check.