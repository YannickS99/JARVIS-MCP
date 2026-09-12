# syntax=docker/dockerfile:1

# ---------- Build ----------
# Der Build laeuft im Container, damit auf JARVIS weder JDK noch Maven installiert sein muessen.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# Zuerst nur die Build-Beschreibung kopieren: Solange sich die Abhaengigkeiten nicht aendern,
# bleibt diese Schicht im Cache und der Build bleibt schnell.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -DskipTests package \
    && mv target/*.jar application.jar

# ---------- Vorbereitung der Laufzeit ----------
# Das Jar wird ausgepackt und einmal probeweise hochgefahren. Der Probelauf haelt fest, welche
# Klassen dabei geladen werden, und legt sie in einem Archiv ab; beim echten Start liest die JVM
# sie daraus statt sie erneut einzeln aus dem Jar zu ziehen und zu pruefen. Das halbiert die
# Startzeit ungefaehr - spuerbar bei jedem Neustart des Containers nach einem Deploy.
FROM eclipse-temurin:25-jdk AS runtime-prep
WORKDIR /app
COPY --from=build /build/application.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --destination extracted

# spring.context.exit=onRefresh faehrt den Kontext hoch und sofort wieder herunter - es wird kein
# Port belegt und keine Verbindung zu Home Assistant gebraucht. Schlaegt der Lauf doch fehl, bleibt
# das Archiv einfach aus: Die JVM verwendet dann keins und startet wie zuvor.
RUN java -XX:ArchiveClassesAtExit=application.jsa \
        -Dspring.context.exit=onRefresh \
        -jar extracted/application.jar || true

# ---------- Laufzeit ----------
FROM eclipse-temurin:25-jre AS runtime

# curl wird ausschliesslich fuer den Container-Healthcheck weiter unten benoetigt.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 1001 jarvis \
    && useradd --system --uid 1001 --gid jarvis --home /app jarvis
WORKDIR /app

COPY --from=runtime-prep --chown=jarvis:jarvis /app/extracted/ ./
COPY --from=runtime-prep --chown=jarvis:jarvis /app/application.jsa application.jsa

USER jarvis
# 8098 spricht MCP, 8099 traegt den Health-Endpunkt fuer das Monitoring Tool.
EXPOSE 8098 8099

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -fsS http://localhost:8099/actuator/health | grep -q '"status":"UP"' || exit 1

# MaxRAMPercentage statt fester Heap-Groesse, damit ein Speicherlimit am Container wirkt.
# Der serielle Sammler passt zu einem Dienst dieser Groesse: Er braucht selbst keine eigenen
# Threads im Hintergrund und hat bei kleinem Heap die kuerzesten Pausen.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:SharedArchiveFile=application.jsa"
ENTRYPOINT ["java", "-jar", "application.jar"]
