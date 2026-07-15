# --- Stage 1: Build source into a JAR ---
FROM maven:3.9.7-eclipse-temurin-17 AS build
WORKDIR /app

# Copy Maven wrapper and project files first for better layer caching.
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw mvnw
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B dependency:go-offline

# Copy source and build the application.
COPY src ./src
RUN ./mvnw -B clean package -DskipTests

# --- Stage 2: Run with a minimal JRE image ---
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=build /app/target/*.jar app.jar
RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
