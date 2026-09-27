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
COPY --from=build /app/target/storage-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
