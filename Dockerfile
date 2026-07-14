FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY . .

RUN mvn clean package -DskipTests


FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system --gid 10001 bema \
    && useradd --system --uid 10001 --gid bema --home-dir /app --shell /usr/sbin/nologin bema

WORKDIR /app

COPY --chown=bema:bema --from=build /app/target/gateway-0.0.1-SNAPSHOT.jar app.jar

USER bema

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
