FROM maven:3.9.16-eclipse-temurin-21@sha256:2b4496088e7b80ae10a8c9f74e574ea21380325a006ec684532ad6bad5bc7273 AS build

WORKDIR /app

COPY . .

RUN mvn clean package -DskipTests


FROM eclipse-temurin:21.0.11_10-jre-jammy@sha256:d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13

RUN groupadd --system --gid 10001 bema \
    && useradd --system --uid 10001 --gid bema --home-dir /app --shell /usr/sbin/nologin bema

WORKDIR /app

COPY --chown=bema:bema --from=build /app/target/gateway-0.0.1-SNAPSHOT.jar app.jar

USER bema

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
