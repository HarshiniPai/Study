# Kanban Studio

## Development

```bash
npm install
npm run dev
```

## Static build

```bash
npm run build
```

The static export is written to `out/` and served at `/` by the Spring Boot backend in the integrated Docker app. The board and sign-in state are loaded from the backend API.

## Tests

```bash
npm run test:unit
npm run test:e2e
```

The end-to-end tests build the static export and run it through the Spring Boot backend; JDK 25 must be installed.
