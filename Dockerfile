FROM maven:3.10.0-eclipse-temurin-21-noble AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21.0.13.1_2-jre-noble
WORKDIR /app
COPY --from=build /workspace/target/database-modeling-lab-0.1.0-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
