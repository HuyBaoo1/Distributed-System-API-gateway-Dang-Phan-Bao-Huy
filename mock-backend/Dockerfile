# --- Stage 1: Build source into a JAR ---
FROM maven:3.9.7-eclipse-temurin-17 AS build
WORKDIR /app

# Copy maven wrapper and project files first for caching
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw mvnw
RUN mvn -B dependency:go-offline

# Copy source and build
COPY src ./src
RUN mvn -B clean package -DskipTests

# --- Stage 2: Run with minimal JRE image ---
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Create non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy built JAR from builder stage
COPY --from=build /app/target/*.jar app.jar
RUN chown -R appuser:appgroup /app

USER appuser

# Mock backend default port is 8081
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
