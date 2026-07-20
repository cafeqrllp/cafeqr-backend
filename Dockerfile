# Build stage
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# Download dependencies to cache them in Docker layer
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

# Run stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Runtime health checks use curl.
RUN apk add --no-cache curl

COPY --from=build /app/target/*.jar app.jar

# Expose the port (Render will use this)
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

