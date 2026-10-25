# Build with the Maven wrapper, then run the jar on a JRE-only image so the
# final image doesn't carry Maven or the JDK.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# dependencies first, so a code change doesn't re-download them
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q dependency:go-offline

COPY src src
RUN ./mvnw -q package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/users-*.jar app.jar

# User Management keeps nothing on disk (its tables are in Postgres), so it doesn't need root
RUN useradd --system app
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
