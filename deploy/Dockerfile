# Production-style image for the API and the worker (same jar, different settings). NOT built or run in the
# environment where this was written (Docker was unavailable), so treat it as a reviewed starting point, not a tested one.
#
#   docker build -f deploy/Dockerfile -t protik:local .

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre
# FFmpeg is the only extra program; ffprobe ships with it.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin protik \
 && mkdir -p /data/objects && chown -R protik:protik /data
COPY --from=build /src/target/protik-*.jar /app/protik.jar
USER 10001:10001
WORKDIR /tmp
ENV PROTIK_STORAGE_ROOT=/data/objects
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/protik.jar"]
