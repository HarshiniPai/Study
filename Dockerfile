FROM node:22-alpine AS frontend-build

WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-25 AS backend-build

WORKDIR /app/backend
COPY backend/pom.xml ./
RUN mvn -q dependency:go-offline
COPY backend/src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:25-jre

WORKDIR /app/backend

COPY --from=backend-build /app/backend/target/app.jar ./app.jar
COPY --from=frontend-build /app/frontend/out /app/frontend/out

ENV DATABASE_PATH=/data/kanban.sqlite3

EXPOSE 8000

CMD ["java", "-jar", "app.jar"]
