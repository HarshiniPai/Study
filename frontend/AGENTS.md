# Frontend Guide

## Current application

This directory contains a Next.js App Router frontend for Kanban Studio. The production build uses Next.js static export and writes assets to `out/`; the Spring Boot backend serves those assets at `/`. The board loads from and saves to the authenticated FastAPI API. The frontend keeps an optimistic in-memory copy while editing and restores the previous state when a save fails.

## Structure

- `src/app/page.tsx` renders the board at `/`.
- `src/app/layout.tsx` defines the root document, fonts, and page metadata.
- `src/app/globals.css` imports Tailwind CSS and defines the app's color and surface variables.
- `src/components/AuthGate.tsx` checks the backend session and gates the board behind sign-in.
- `src/components/KanbanBoard.tsx` owns board state and drag-and-drop behavior.
- `src/components/KanbanColumn.tsx` renders an editable column, its cards, and the add-card form.
- `src/components/KanbanCard.tsx` renders sortable/editable cards; `KanbanCardPreview.tsx` renders the drag overlay.
- `src/components/NewCardForm.tsx` handles adding a card.
- `src/lib/kanban.ts` defines the board, column, and card types, sample data, card movement, and ID creation.
- `src/**/*.test.ts` and `src/**/*.test.tsx` contain Vitest unit/component tests.
- `tests/` contains Playwright end-to-end tests.
- `next.config.ts` enables static export for the production build.

## Conventions

- Keep board/domain types and pure board operations in `src/lib/kanban.ts` when they are shared by components.
- Keep interactive board UI in small components under `src/components/`; follow the existing client-component pattern where React state or browser interaction is needed.
- Use the existing Tailwind styling and CSS variables, including the project palette, rather than introducing a separate styling system.
- Add or update focused Vitest tests for component and domain behavior. Use Playwright for important user journeys that need a real browser.
- Preserve type safety and accessible labels/roles when adding or changing controls.

## Commands

Run these from `frontend/`:

- `npm run dev` starts the development server.
- `npm run lint` runs ESLint.
- `npm run test:unit` runs Vitest.
- `npm run test:e2e` builds the static app and runs Playwright against it through the Spring Boot backend; JDK 25 must be installed.
- `npm run test:all` runs unit and end-to-end tests.
- `npm run build` creates the Next.js production build.

The board and authentication are connected to the backend. AI integration is planned work; do not assume it is already implemented.
