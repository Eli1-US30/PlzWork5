# Build stage — compile the Java project
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Run stage — just the JRE, no Maven needed
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the fat jar from build stage
COPY --from=build /app/target/PlzWork5-1.0-SNAPSHOT-jar-with-dependencies.jar app.jar

# Create data directory for persistent files
RUN mkdir -p /app/data

# Run the predictor
CMD ["java", "-jar", "app.jar"]