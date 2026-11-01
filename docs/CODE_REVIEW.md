# Code review

Date: 2026-10-31. Scope: all of `backend/app/` and `frontend/src/`, plus the Docker and script setup.

State at review time: 20 backend tests, 12 frontend unit tests, 7 Playwright tests, and lint all pass. The container smoke test also passes (`/`, `/api/health`, auth gate, board load). Secrets and the SQLite files are gitignored.

Overall the code is small, readable, and close to the plan. Validation is strong on the backend: board saves are checked against the fixed columns, AI actions are applied to a copy and rejected as a whole if any action is invalid, and only free OpenRouter models can be selected. The findings below are ordered by severity.

## Bugs

### 1. AI chat can overwrite changes made while it is thinking (high) - FIXED

Fixed 2026-10-31: `respond_to_board` now returns validated actions, and `ai_chat` applies them to the board as re-read after the AI call. If an action no longer fits (for example, its card was deleted meanwhile), the route returns 409 and saves nothing. Covered by `test_ai_chat_keeps_edits_saved_while_assistant_responds` and `test_ai_chat_rejects_actions_for_cards_deleted_meanwhile`.

`backend/app/main.py:101-120`. `ai_chat` reads the board, waits for OpenRouter (up to 45 s with free models), then saves the full board it computed from that earlier read. The board stays editable during that time (`AIChatSidebar` does not disable it). Any card edit, move, or rename the user saves in the meantime is silently replaced when the AI result is saved, and the frontend reload then shows the change vanishing.

Repro: ask the AI to "add a card to Backlog", then immediately rename the Done column. When the answer arrives, the rename is gone.

Fix options (pick one): re-read the board after the AI call and apply the actions to that fresh copy before saving, or disable board editing while a chat request is in flight. Re-applying actions to the fresh board is the more robust choice and keeps the UI usable.

### 2. Card edit form shows stale text after the board changes (medium) - FIXED

Fixed 2026-10-31: the Edit button loads the current card title and details into the form. Covered by `frontend/src/components/KanbanCard.test.tsx`.

`frontend/src/components/KanbanCard.tsx:21-22`. `title` and `details` are copied into local state once, when the card first mounts. When the card changes later through an AI update (the board reloads without remounting cards) or a reverted save, opening "Edit" shows the old values. Saving that form then writes the old title back and undoes the AI's edit.

Verified with a temporary component test: after rerendering a card from "Old title" to "AI title", the edit input contained "Old title".

Fix: set `title`/`details` from `card` when entering edit mode (in the Edit button's `onClick`), and add a regression test.

### 3. Selecting text in the card edit form starts a drag (low)

`frontend/src/components/KanbanCard.tsx:49-50`. The drag `listeners` are spread on the whole `<article>`, including the edit form. The Edit and Remove buttons stop pointer propagation, but the title input and details textarea do not, so click-and-drag to select text moves the card instead after 6 px.

Fix: skip the listeners while `isEditing` (for example, `{...(isEditing ? {} : listeners)}`).

### 4. Failed chat requests leave unpaired messages in the history (low)

`frontend/src/components/AIChatSidebar.tsx:48-52`. The user's message is added to `messages` before the request. If the request fails, it stays in the history with no assistant reply, so the next request sends two user turns in a row. Some models handle this poorly. Either remove the message on failure or keep it out of the history that is sent.

### 5. Non-JSON error responses show a parser error to the user (low)

`frontend/src/components/AIChatSidebar.tsx:63`. `response.json()` runs before checking `response.ok`. A plain-text error (for example, an unhandled 500 returning "Internal Server Error") throws a JSON syntax error, and that raw message is shown in the chat. Check `response.ok` first, or catch the parse failure and fall back to the status-code message.

### 8. Every live AI request was rejected by OpenRouter (high) - FIXED

Found during the first live test. `backend/app/openrouter.py` sent all 4 free models in OpenRouter's `models` fallback list, but OpenRouter accepts at most 3 (`'models' array must have 3 items or fewer`), so every chat request failed with HTTP 400. The mocked tests did not catch it. Fixed 2026-11-01 by sending the chosen model plus at most two fallbacks (`MAX_MODELS_PER_REQUEST`); the live 2+2 check now passes.

## Gaps against the plan

### 6. Cards cannot be moved with the keyboard

`frontend/src/components/KanbanBoard.tsx:30-34`. Only `PointerSensor` is registered, so cards are focusable (dnd-kit adds `role="button"` and `tabIndex`), but nothing happens on key presses. Part 10 requires the board to be keyboard usable. Add dnd-kit's `KeyboardSensor` with `sortableKeyboardCoordinates`.

### 7. An expired session leaves the user on a board that cannot save

The session key is regenerated on every backend restart (documented). After a restart, every save fails with "Could not save board (401). Changes were reverted." and the user is never sent back to sign in. Treat a 401 from `/api/board` or `/api/ai/chat` as signed out in `AuthGate`.

## Minor

- `KanbanBoard.tsx:123-136`: dropping a card back where it started still sends a PUT, because `moveCard` returns the same columns. Skip the save when `moveCard` returns the original array.
- `Dockerfile:10`: `ghcr.io/astral-sh/uv:latest` is unpinned, so image builds can change without code changes. Pin a version.
- Tests: the `StarletteDeprecationWarning` about `httpx` in `TestClient` will become an error in a future Starlette release.
- `frontend/AGENTS.md` still says "AI integration is planned work", but it is implemented.

## Suggested order

1. Add the keyboard sensor (#6) before the Part 10 manual accessibility review.
2. Handle the rest as small follow-ups.
