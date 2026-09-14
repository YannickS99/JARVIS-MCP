# JARVIS-MCP

Die Integrationsschicht zwischen dem **JARVIS-AIService** und den Systemen im Haus. JARVIS-MCP
macht vorhandene Schnittstellen über das [MCP-Protokoll](https://modelcontextprotocol.io) als
Werkzeuge für die KI verfügbar — ohne dass der AIService für jedes System eigene
Integrationslogik mitbringen muss.

**Adapter, nicht Neuimplementierung:** Home Assistant bleibt Home Assistant, das Monitoring Tool
behält seine REST-API. JARVIS-MCP übersetzt lediglich zwischen ihnen und dem Sprachmodell.

```
LLM → MCP-Werkzeug set_light_power → JARVIS-MCP → Home-Assistant-REST-API → Home Assistant
```

## Werkzeuge

| Werkzeug | Parameter | Wirkung |
|---|---|---|
| `set_area_lights_power` | `area`, `power` | Schaltet alle Lichter eines Bereichs an/aus |
| `set_light_power` | `light`, `power` | Schaltet ein einzelnes Licht über seinen Anzeigenamen |
| `run_ha_routine` | `name` | Löst eine Home-Assistant-Szene oder ein -Skript aus |
| `get_lights_status` | `area` (optional) | Sagt, welche Lichter gerade an sind — im ganzen Haus oder in einem Bereich |
| `get_light_status` | `light` | Sagt, ob ein einzelnes Licht an oder aus ist |

`power` nimmt `on` bzw. `off` entgegen (und ein paar naheliegende Varianten wie `an`/`aus`).
Namen werden unabhängig von Groß-/Kleinschreibung und Umlautschreibweise erkannt: `Büro`,
`buero` und `BÜRO` finden denselben Eintrag. **Bereiche, Lichter und Routinen kommen alle aus
Home Assistant** — es gibt nichts doppelt zu pflegen. Ist ein Name nicht eindeutig oder unbekannt, kommt
eine Antwort mit den möglichen Namen zurück statt eines Protokollfehlers — das Modell kann es
damit gleich noch einmal richtig versuchen.

Die beiden Status-Werkzeuge fragen Home Assistant **bei jedem Aufruf frisch** — anders als die
Namensauflösung, die aus einem warmgehaltenen Index kommt. Ein zwischengespeicherter Schaltzustand
wäre schlicht falsch, sobald jemand einen Schalter drückt. Geholt wird er über dieselbe
Template-Engine wie die Bereiche (`POST /api/template`), weil nur sie die Bereichszuordnung einer
Entität herausgibt und die Antwort dabei ein paar hundert Byte groß ist statt der mehreren hundert
Kilobyte von `/api/states`. Ein Licht, das Home Assistant als `unavailable` meldet, wird eigens
genannt statt stillschweigend als „aus" gezählt.

Neue „Protokolle" entstehen rein in Home Assistant: Wer dort eine Szene oder ein Skript anlegt,
kann es sofort über `run_ha_routine` ansprechen — an JARVIS-MCP ist dafür nichts zu ändern.

## Betrieb

```bash
cp .env.example .env      # JARVIS_MCP_AUTH_TOKEN und HA_TOKEN eintragen
docker compose up -d --build
```

| | |
|---|---|
| MCP-Endpunkt | `http://jarvis:8098/mcp` (Streamable HTTP, Bearer-Token) |
| Health | `http://jarvis:8099/actuator/health` (ohne Token, fürs Monitoring Tool) |

Beide Ports binden an `0.0.0.0`, nicht an `127.0.0.1` — sonst wären sie weder über Tailscale
erreichbar noch aus einem anderen Container. Genau das brauchen aber beide Abnehmer: Der
JARVIS-AIService läuft in einem eigenen Compose-Stack, und die Health-Prüfungen des Monitoring
Tools laufen in dessen Backend-Container. Eine eigene UFW-Regel ist nicht nötig, weil Docker
seine veröffentlichten Ports per DNAT an der UFW-Kette vorbeiführt.

Im Monitoring Tool als HTTP-Health-Check eintragen:

```
http://jarvis:8099/actuator/health
```

Das ist ein fremder Stack aus Sicht des Monitoring-Backends — der Rückweg läuft über den Host,
der Selbstaufruf-Fallstrick des eigenen Stacks greift hier also nicht. Voraussetzung ist der dort
bereits gesetzte `extra_hosts`-Eintrag `jarvis:host-gateway`.

**Es gibt nichts zu konfigurieren.** Bereiche, Lichter und Routinen liest JARVIS-MCP aus Home
Assistant — die Bereiche über dessen Template-Engine (`areas()` / `area_name()` via
`POST /api/template`), den Rest über `GET /api/states`. Ein neuer Bereich oder ein neues Licht in
Home Assistant ist damit sofort ansprechbar.

Nur für den Ausnahmefall, dass ein Bereich anders angesprochen werden soll, als er in Home
Assistant heißt (Home Assistant kennt dafür eigene Aliasse, gibt sie aber über keine
REST-Schnittstelle heraus), gibt es eine Zeile in der `.env`:

```bash
SPRING_APPLICATION_JSON={"jarvis-mcp":{"home-assistant":{"areas":[{"id":"arbeitszimmer","names":["Büro"]}]}}}
```

Die echten Namen aus Home Assistant funktionieren weiterhin. Meist ist es einfacher, den Bereich
in Home Assistant gleich so zu nennen.

## Entwicklung

```bash
./mvnw test                 # alle Tests, inkl. MCP-Durchlauf gegen einen Home-Assistant-Stub
./mvnw spring-boot:run      # lokal, HA_BASE_URL und HA_TOKEN als Umgebungsvariablen
```

### Aufbau

```
tools/homeassistant/   Werkzeuge, REST-Client, Entitäten-Index und Konfiguration dieser Integration
security/              Bearer-Token-Prüfung vor dem MCP-Endpunkt
common/                Namensnormalisierung, von allen Modulen genutzt
```

Jede Integration liegt in einem eigenen Paket unter `tools/` und bringt Werkzeuge, Client und
Konfiguration selbst mit. Eine weitere Integration (Monitoring Tool, Obsidian, Dateien) kommt als
zusätzliches Paket dazu — an den bestehenden ist dafür nichts zu ändern.

### Wo die Geschwindigkeit herkommt

Die Namensauflösung ist der einzige Punkt, an dem ein Werkzeugaufruf teuer werden könnte:
`set_light_power` und `run_ha_routine` brauchen dafür `GET /api/states`, und das liefert sämtliche
Entitäten samt aller Attribute; die Bereiche kosten einen weiteren Aufruf. Drei Entscheidungen
halten das aus dem Antwortweg heraus:

- **Entitäten und Bereiche werden im Hintergrund warmgehalten** (Standard: alle 60 s). Ein
  Werkzeugaufruf kostet im Normalfall einen Hash-Zugriff, keinen HTTP-Aufruf. Ein unbekannter Name
  löst ein sofortiges Nachladen aus — gedeckelt, damit wiederholte Fehlgriffe Home Assistant nicht
  überziehen.
- **Die Antwort wird im Datenstrom gelesen**, nicht in Objekte verwandelt: Aus jedem Eintrag
  bleiben `entity_id` und `friendly_name`, alles andere wird übersprungen.
- **Ein einziger HTTP-Client** hält die Verbindung zu Home Assistant offen, und alle Schreibweisen
  eines Namens sind beim Aufbau des Index bereits ausgerechnet.

Dazu laufen Anfragen auf virtuellen Threads, und das Image bringt ein vorbereitetes Klassenarchiv
mit, das die Startzeit nach einem Deploy etwa halbiert.

## Release

```bash
./release.sh 1.0.0
```

Führt den kompletten Git-Flow-Ablauf durch: Release-Branch, Versions-Bump in der `pom.xml`,
Commit, Merge nach `main` und `develop`, Tag, Push beider Branches und `develop` auf die nächste
Patch-Version mit `-SNAPSHOT`. Voraussetzung ist `git flow` (macOS: `brew install git-flow-avh`).
Schlägt ein Schritt fehl, bricht das Skript sofort ab und sagt, in welchem Zustand das Repository
zurückbleibt.

## Verwandte Projekte

- **JARVIS-AIService** — der MCP-Client dieses Servers
- **JARVIS-Pilot** — Oberfläche des Gesamtsystems
- **Monitoring Tool** — überwacht diesen Dienst; nächster Kandidat für ein eigenes Tool-Modul
