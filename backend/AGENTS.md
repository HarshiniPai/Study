# Backend Guide

The backend is a Java 25 Spring Boot 4 application built with Maven. Run backend commands from `backend/` with the Maven wrapper (`./mvnw`); only a JDK 25 is required locally.

- `./mvnw test` runs the JUnit tests. Single class or method: `./mvnw test -Dtest=ApiControllerTest#loginLogoutFlow`.
- `./mvnw spring-boot:run` serves the API on port 8000 plus the exported frontend from `../frontend/out` (build the frontend first).
- `./mvnw -DskipTests package` builds `target/app.jar`.

Layout under `src/main/java/com/kanbanstudio/`:

- `web/ApiController.java` exposes the `/api/` routes; `ApiExceptionHandler` turns errors into `{"detail": ...}` responses, which the frontend reads.
- `db/BoardRepository.java` owns the SQLite schema, initialization (on startup, never overwriting existing data), and board queries. `DatabaseConfig` sets up the SQLite data source with foreign keys enforced.
- `model/` holds the request and response records. Their JSON field names are the API contract with the frontend.
- `ai/` holds the OpenRouter client, the AI service, and `BoardActions`, which applies validated card actions.

Spring Boot serves the statically exported Next.js app from `app.static-dir` (default `../frontend/out`) at `/`; keep browser assets and API routes on the same origin. The MVP sign-in uses the servlet session (in memory, so a restart signs users out). Use Spring's `JdbcTemplate` with `TransactionTemplate` for data access rather than adding an ORM.

Configuration comes from `src/main/resources/application.properties` and environment variables: `DATABASE_PATH`, `STATIC_DIR`, `OPENROUTER_API_KEY`, `OPENROUTER_MODEL`. The project-root `.env` is imported for local runs. The API key must never be logged or returned to the frontend. Routine tests mock HTTP with `MockRestServiceServer` and mock `AiService` with `@MockitoBean`; the opt-in live check is `java -jar target/app.jar --spring.main.web-application-type=none --app.check-openrouter=true`.

Add focused tests for API and backend behavior; route tests use MockMvc in `ApiControllerTest`, which resets the database before each test.
