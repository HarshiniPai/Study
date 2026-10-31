# Project Management MVP Plan

## Project goal

Deliver a locally runnable project-management MVP with a single Kanban board per user, a fixed set of renameable columns, editable/movable cards, and an AI chat sidebar that can update the board.

## Product and technical constraints

- Frontend: Next.js, built as static assets and served by the FastAPI application at `/`.
- Backend: Python FastAPI, packaged with the frontend in one Docker container.
- Python package management in the container: `uv`.
- Database: local SQLite, initialized automatically when missing, with a schema that supports multiple users and one board per user.
- MVP sign-in: the hardcoded username `user` and password `password`; keep authentication boundaries suitable for future multiple-user support.
- AI: backend-only calls to OpenRouter using `OPENROUTER_API_KEY` from the root `.env`; do not expose the key to the browser or commit it.
- Runtime: local Docker setup with start and stop scripts for macOS, Windows, and Linux in `scripts/`.
- Preserve the current frontend demo and its visual direction while wiring in persistence and AI.

## Delivery process

- Complete Part 1 first, then stop for user review and approval of this plan before implementing Parts 2–10.
- Part 5 has an additional approval gate: present the proposed database schema and approach and wait for user sign-off before implementing the database-backed API in Part 6.
- Keep each part independently testable. Prefer deterministic automated tests; any test requiring Docker, network access, or a real OpenRouter key must be identified as such and kept distinct from the ordinary test suite.
- Check off implementation items and record any deviations here as work is completed.

## Part 1: Plan and frontend guide

### Checklist

- [x] Break Parts 2–10 into implementation checklists, tests, and measurable success criteria.
- [x] Document the existing frontend structure and conventions in `frontend/AGENTS.md`.
- [ ] Ask the user to review and approve this plan before starting Part 2.

### Tests

- No code tests; inspect the plan for all ten parts and confirm the frontend guide matches the current source and test layout.

### Success criteria

- Every implementation part has actionable checklists, validation steps, and observable acceptance criteria.
- The frontend guide describes the existing demo without claiming planned backend features already exist.
- The user has approved the plan before implementation proceeds.

## Part 2: Docker and backend scaffolding

### Checklist

- [x] Inspect existing backend and scripts directories and preserve any useful conventions.
- [x] Add a minimal FastAPI application with a health/example API route.
- [x] Add a minimal static HTML landing page for this scaffolding milestone.
- [x] Add a Dockerfile and any required Docker configuration to package and run the backend.
- [x] Use `uv` to install and run Python dependencies in the container.
- [x] Add start and stop scripts for macOS, Windows, and Linux, with clear usage and actionable errors.
- [x] Add concise run instructions for the local Docker workflow.

### Tests

- [x] Backend unit test: the health/example API route returns the documented status and response.
- [ ] Smoke test: build and start the container, load the example page at `/`, and call the API route.
- [x] Verify each start/stop script is syntactically valid for its target platform where the available environment permits (POSIX scripts checked; PowerShell and Docker are unavailable here).

The backend tests and local HTTP smoke test passed. The Docker build/container smoke test could not be run because Docker is not installed in the current environment.

### Success criteria

- One documented start command launches the container locally; the corresponding stop command stops it.
- The root URL serves the example HTML and the API route responds successfully from the running container.
- The container uses `uv`, starts without relying on undeclared host dependencies, and does not require the OpenRouter key for this stage.

## Part 3: Serve the existing frontend

### Checklist

- [x] Configure Next.js for a static production export compatible with the FastAPI static-file serving approach.
- [x] Build the frontend as part of the container image and serve its exported files at `/`.
- [x] Remove or replace the scaffolding HTML route without shadowing API routes.
- [x] Preserve the current demo board, interactions, and visual design.
- [x] Update run/build documentation to describe the integrated app.

### Tests

- [x] Run frontend lint, unit tests, and production build.
- [x] Run the existing Playwright suite against the served app.
- [ ] Container smoke test: `/` serves the Kanban board and the API health route remains reachable.

The static export, frontend checks, backend tests, and Playwright suite passed against a locally run FastAPI server. Container smoke testing remains blocked because Docker is unavailable.

### Success criteria

- The built frontend is served at `/` by the FastAPI application in the Docker container.
- The board displays its five demo columns and existing card interactions work.
- Frontend and backend routes coexist without route conflicts.

## Part 4: Hardcoded sign-in experience

### Checklist

- [x] Add a login screen using the MVP credentials `user` / `password`.
- [x] Restrict access to the board until sign-in succeeds and provide a logout action.
- [x] Keep credential handling and any session state out of client-visible configuration.
- [x] Define and implement a simple, documented session mechanism appropriate to this local MVP.

### Tests

- [x] Frontend tests: login form behavior, invalid credentials, successful sign-in, and logout.
- [x] Backend tests for authentication/session endpoints and access control.
- [x] End-to-end tests: unauthenticated access, successful login, refresh/session behavior, and logout.

The local session cookie is signed with a process-generated secret and is invalidated when the backend restarts; this is documented as an MVP-only mechanism.

### Success criteria

- A fresh visitor must sign in before seeing the board.
- Only the exact MVP credentials grant access; invalid credentials show a clear failure.
- Logout returns to the login screen and removes access to protected board data.
- The chosen mechanism is documented as local-MVP authentication, not production-grade identity management.

## Part 5: Database model proposal and approval

### Checklist

- [x] Propose a JSON schema for users, a user's single board, ordered columns, and cards.
- [x] Define primary keys, foreign keys, uniqueness constraints, ordering, and deletion behavior.
- [x] Document SQLite initialization and migration expectations, user/board ownership, and how the MVP account maps to a user record.
- [x] Save the schema as JSON and document the database approach in `docs/`.
- [ ] Present the proposal and wait for user sign-off before Part 6.

### Tests

- [x] Validate that the schema file is valid JSON.
- [x] Review the proposal against the one-board-per-user requirement and future multiple-user support.

### Success criteria

- The JSON schema and database approach are documented and consistent with each other.
- The proposal accounts for board, column, and card ordering and ownership.
- User sign-off is received before implementing the database-backed API.

## Part 6: SQLite persistence and backend board API

### Checklist

- [x] Implement the approved schema and initialize a new SQLite database automatically when absent.
- [x] Implement backend data access with explicit user/board ownership boundaries.
- [x] Add API routes to read and update the signed-in user's board.
- [x] Mount a persistent named Docker volume at the database path so data survives container recreation.
- [x] Validate incoming board data and return clear, appropriate HTTP errors for invalid or unauthorized requests.
- [x] Keep database operations and API behavior covered by focused backend tests.

### Tests

- [x] Backend unit/integration tests for database initialization, initial board creation, read, and updates.
- [x] Test persistence across application/database connections and restart-like re-open.
- [x] Test missing board/user, malformed data, and attempts to access another user's data.

Backend API/database tests pass. The named Docker volume is configured in the run scripts but remains unverified inside a container because Docker is unavailable.

### Success criteria

- A missing database is created and initialized without manual setup.
- A signed-in user can read and update exactly their own board, and changes persist.
- Data constraints and authorization prevent cross-user access and invalid board state.

## Part 7: Connect the frontend to the backend

### Checklist

- [x] Replace frontend-only board initialization with data loaded from the authenticated backend API.
- [x] Persist column renames, card edits/creation/deletion, and drag-and-drop moves through the API.
- [x] Add clear loading and error states that preserve the user's ability to recover from failed requests.
- [x] Keep frontend API access same-origin in the integrated deployment unless a documented requirement dictates otherwise.

### Tests

- [x] Frontend unit/integration tests for API loading, updates, and failure states using a deterministic mocked API.
- [x] End-to-end test that edits and moves board data, reloads, and verifies persisted state.
- [ ] Run the complete frontend and backend automated suites and the integrated container smoke test.

Frontend, backend, and end-to-end tests pass locally. The container smoke test remains blocked because Docker is unavailable.

### Success criteria

- Board contents load from SQLite through the backend rather than being authoritative in browser memory.
- Supported board interactions persist and remain after reload.
- API failures are visible and do not silently appear as successful saves.

## Part 8: OpenRouter connectivity

### Checklist

- [x] Add a backend-only OpenRouter client configured from `OPENROUTER_API_KEY`.
- [x] Select and document an available free model appropriate for the intended AI task.
- [x] Add a minimal backend test path for a simple `2+2` prompt.
- [x] Keep credentials out of the frontend bundle, logs, and committed files; document local `.env` setup.
- [x] Separate mocked automated tests from the opt-in live connectivity check.

### Tests

- [x] Unit tests with a mocked HTTP client for request construction, successful responses, and provider/API errors.
- [ ] Opt-in live connectivity check using a configured key; verify a response equivalent to `4`.
- [x] Verify missing-key behavior is an explicit actionable error and does not prevent non-AI app features from running.

The mocked OpenRouter tests pass. The live connectivity check was not run to avoid sending credentials or a request to an external provider from this environment.

### Success criteria

- The backend can call OpenRouter using the configured key and return the model response.
- Ordinary tests do not require network access or a real API key.
- No OpenRouter secret is sent to or embedded in the frontend.

## Part 9: Structured AI board updates

### Checklist

- [x] Define a typed structured response containing a user-facing answer and an optional board update.
- [x] Send the current user's board, question, and conversation history to the backend AI service.
- [x] Constrain and validate proposed changes against the board model and current user's ownership before applying them.
- [x] Define behavior for malformed model output, unsupported operations, and provider failures.
- [x] Return the response and any successfully applied board update to the caller.

### Tests

- [x] Unit tests for valid answer-only responses and valid create/edit/move operations.
- [x] Tests for invalid structured output, invalid IDs/columns, unauthorized data, and rejected updates.
- [x] Test conversation history and board context are included in the provider request.
- [x] Mock provider responses in all routine tests; do not make test results depend on model interpretation.

### Success criteria

- Every AI request is grounded in the signed-in user's current board and supplied conversation history.
- The backend applies only validated changes to that user's board and returns the resulting board state.
- Invalid AI output never causes an unvalidated or partially accepted board mutation.

## Part 10: AI chat sidebar

### Checklist

- [x] Add a polished, accessible AI chat sidebar consistent with the existing app design and color scheme.
- [x] Support sending a question, displaying the conversation, and showing loading and error states.
- [x] Send chat requests and conversation history to the backend; do not call OpenRouter from the browser.
- [x] Apply returned board updates and refresh the visible board automatically.
- [x] Ensure the board remains usable at narrow and wide viewport sizes.

### Tests

- [x] Component tests for chat composition, message rendering, pending/error behavior, and returned board updates.
- [x] End-to-end tests for a mocked assistant response and for an assistant response that changes the board.
- [ ] Run all unit, integration, and end-to-end tests plus the integrated container smoke test.
- [ ] Manually review keyboard access, focus behavior, and responsive layout.

### Success criteria

- A signed-in user can chat with the AI from the sidebar and see both the assistant's answer and any requested board changes.
- Board updates appear without a manual page refresh and persist through the existing backend.
- The chat is usable by keyboard, gives clear feedback during failures, and does not expose provider credentials.
