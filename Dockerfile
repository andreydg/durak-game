# syntax=docker/dockerfile:1

# --- Maven build (Java 25 matches pom.xml) ---
FROM maven:3.9.15-eclipse-temurin-26-noble AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn -q -DskipTests package

# --- Slim runtime ---
FROM eclipse-temurin:25-jre-noble
WORKDIR /app

RUN groupadd --system spring && useradd --system --gid spring --home /app spring

COPY --from=build /app/target/durak-game-*.jar /app/app.jar
RUN chown spring:spring /app/app.jar

USER spring

EXPOSE 8080

# Render sets PORT; $JAVA_OPTS is a common hook for heap tuning. The JVM defaults the heap to
# 25% of the container limit (128 MiB of Cloud Run's 512 MiB); leave the rest for metaspace,
# threads and network buffers.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
