# JARVIS-MCP

Die Integrationsschicht zwischen dem **JARVIS-AIService** und den Systemen im Haus. JARVIS-MCP
macht vorhandene Schnittstellen über das [MCP-Protokoll](https://modelcontextprotocol.io) als
Werkzeuge für die KI verfügbar — ohne dass der AIService für jedes System eigene
Integrationslogik mitbringen muss.

**Adapter, nicht Neuimplementierung:** Home Assistant bleibt Home Assistant, das Monitoring Tool
behält seine REST-API. JARVIS-MCP übersetzt lediglich zwischen ihnen und dem Sprachmodell.

```
LLM → MCP-Werkzeug set_light_power        → JARVIS-MCP → Home-Assistant-REST-API → Home Assistant
LLM → MCP-Werkzeug set_application_power  → JARVIS-MCP → Monitoring-Tool-REST-API → Docker
```

## Werkzeuge

### Home Assistant — Lichter und Routinen

| Werkzeug | Parameter | Wirkung |
|---|---|---|
| `set_area_lights_power` | `area`, `power` | Schaltet alle Lichter eines Bereichs an/aus |
| `set_light_power` | `light`, `power` | Schaltet ein einzelnes Licht über seinen Anzeigenamen |
| `run_ha_routine` | `name` | Löst eine Home-Assistant-Szene oder ein -Skript aus |
| `get_lights_status` | `area` (optional) | Sagt, welche Lichter gerade an sind — im ganzen Haus oder in einem Bereich |
| `get_light_status` | `light` | Sagt, ob ein einzelnes Licht an oder aus ist |

### Monitoring Tool — Anwendungen auf dem Server

| Werkzeug | Parameter | Wirkung |
|---|---|---|
| `get_applications_status` | `application` (optional) | Sagt, wie es den Anwendungen geht — wie viele laufen, ob etwas ausgefallen oder absichtlich gestoppt ist; mit Namen nur für diese eine |
| `set_application_power` | `application`, `power` | Startet bzw. stoppt den Docker-Container der genannten Anwendung |

Angesprochen wird über den **Anwendungsnamen**, nicht über den Containernamen: „Monetheus" statt
`monetheus-backend-1`. Der Containername ist technisch gewachsen, der Anwendungsname ist der, unter
dem der Dienst im Haus bekannt ist — und der, den man ausspricht. Aufgelöst wird er hier und nicht im
Monitoring Tool: Dort steht ein Dienst mit Kennungen, hier steht der Nutzer mit einer Äußerung, und
die Rückfrage bei Mehrdeutigkeit gehört an das Ende, an dem sie gestellt werden kann.

Eine so gestoppte Anwendung gilt als **absichtlich ausgeschaltet** und nicht als Ausfall — das
Monitoring Tool merkt sich den Soll-Zustand, JARVIS-MCP muss dafür nichts nachbauen. Ist die
Anwendung schon im gewünschten Zustand, wird gar nicht geschaltet, sondern gesagt, dass nichts zu tun
war. Und lehnt das Monitoring Tool den Eingriff ab — geschützter Container, Steuerung abgeschaltet,
Docker nicht erreichbar —, kommt dessen Begründung als Antwort zurück statt eines Protokollfehlers.

Zwischengespeichert wird hier **nichts**, auch nicht die Namen: Sie kommen aus derselben schlanken
Antwort wie die Zustände, und die darf nicht altern. Anders als bei Home Assistant kostet das nichts —
der Aufruf geht an einen Dienst im selben Docker-Netz.

### Gemeinsames

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

## Für den semantischen Cache des AIService

Der [JARVIS-AIService](https://github.com/YannickS99/JARVIS-AIService) überspringt bei bekannten
Befehlen das Sprachmodell und ruft das Werkzeug direkt auf (Anforderungskatalog
**JARVIS-SemanticCache**). Zwei Dinge braucht er dafür von hier — beide über bestehende
MCP-Mechanismen, damit er keine werkzeugspezifische Logik mitbringen muss.

**1. Tool-Annotations: Was darf wiederholt werden?**

Jedes Werkzeug trägt die MCP-Standardhinweise `readOnlyHint` und `idempotentHint`. Gecacht wird
nur, was zustandsverändernd *und* idempotent ist:

| Werkzeug | `readOnlyHint` | `idempotentHint` | cachebar |
|---|---|---|---|
| `set_area_lights_power` | `false` | `true` | ja |
| `set_light_power` | `false` | `true` | ja |
| `set_application_power` | `false` | `true` | ja |
| `run_ha_routine` | `false` | `false` | nein |
| `get_lights_status`, `get_light_status`, `get_applications_status` | `true` | — | nein |

`run_ha_routine` ist bewusst konservativ: Szenen und Skripte werden in Home Assistant frei
definiert und garantieren keine reine Zustandssetzung — ein Skript darf etwas umschalten. Diese
Hinweise sind damit keine Dokumentation, sondern steuern Verhalten auf der anderen Seite.

**2. Entity-Resources: Wie heißen die Dinge?**

Damit der Cache „Wohnzimmer" in einer Äußerung als Platzhalter erkennt, braucht er dieselben
Namen, die hier ohnehin warmgehalten werden. Sie kommen als **Resources** (reiner Datenabruf, kein
Seiteneffekt — das Sprachmodell sieht sie nicht):

| URI | Inhalt |
|---|---|
| `homeassistant://areas-and-entities` | Bereiche samt konfigurierter Zusatznamen, Lichter, Routinen |
| `monitoring://applications` | die im Monitoring Tool hinterlegten Anwendungen |

Beide liefern dasselbe Format mit dem MIME-Typ `application/vnd.jarvis.entities+json`:

```json
{"entities": [
  {"type": "area",  "name": "Arbeitszimmer", "ref": "arbeitszimmer", "aliases": ["Büro"]},
  {"type": "light", "name": "Stehlampe",     "ref": "light.stehlampe", "aliases": []}
]}
```

`type` heißt wie der Werkzeugparameter, der den Namen entgegennimmt (`area`, `light`,
`application`) — daran erkennt der AIService, welcher Name in welches Argument gehört, und lernt
kein `set_light_power(light="Wohnzimmer")`, wenn „Wohnzimmer" ein Bereich ist.

**Der AIService sucht die Resources am MIME-Typ, nicht an der URI.** Ein neues Werkzeugmodul macht
seine Namen also bekannt, indem es eine weitere solche Resource anbietet
(`common/EntityCatalog.java`) — dort ist dafür nichts zu ändern. Gelesen wird im
Hintergrundtakt, ein Abruf kostet keinen zusätzlichen Aufruf bei Home Assistant.

## Betrieb

```bash
cp .env.example .env      # JARVIS_MCP_AUTH_TOKEN, HA_TOKEN und MONITORING_TOKEN eintragen
docker compose up -d --build
```

`MONITORING_TOKEN` muss demselben Wert entsprechen, der im Monitoring Tool als
`MONITORING_INTEGRATION_TOKEN` hinterlegt ist. Fehlt er, scheitern nur die Monitoring-Werkzeuge (mit
einer deutlichen Warnung beim Start) — der Rest läuft weiter; wer sie gar nicht will, setzt
`MONITORING_ENABLED=false`, dann tauchen sie nicht einmal in der Werkzeugliste auf.

Der Stack hängt sich an das gemeinsame Netz **`jarvis-net`** und spricht das Monitoring-Backend
direkt unter `monitoring-backend:8080` an. Das ist nicht nur bequemer als ein Hostport, sondern
notwendig: Das Monitoring-Backend ist bewusst an `127.0.0.1` gebunden und aus einem Bridge-Container
deshalb nicht über `host.docker.internal` erreichbar — und es an `0.0.0.0` freizugeben würde auch
dessen ungeschütztes `/api/v1` ins Netz stellen.

**Von Hand ist dafür nichts zu tun.** Beide Stacks deklarieren das Netz gleich (Name `jarvis-net`,
Treiber `bridge`): Wer zuerst hochkommt, legt es an, der zweite benutzt es mit, und der letzte
`docker compose down` räumt es wieder weg. Damit gibt es **keine Startreihenfolge** — beide Dienste
lassen sich unabhängig und in beliebiger Reihenfolge ausrollen und neu starten, und ein versehentlich
entferntes Netz heilt sich beim nächsten `up` selbst.

Die naheliegenden Alternativen sind beide schlechter: Gehörte das Netz einem der Stacks (`external`
auf der anderen Seite), könnte dessen `docker compose down` den anderen am Hochfahren hindern; wäre
es auf beiden Seiten `external`, müsste es einmal pro Host von Hand angelegt werden und ohne es
startet keiner der beiden. Wichtig ist nur, dass **beide Seiten dieselben Angaben** machen — weichen
Name oder Treiber ab, verweigert Compose den Start.

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

**Über die Token hinaus gibt es nichts zu konfigurieren.** Bereiche, Lichter und Routinen liest
JARVIS-MCP aus Home Assistant — die Bereiche über dessen Template-Engine (`areas()` / `area_name()` via
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

Ebenso für die Anwendungen: Welche es gibt, wie sie heißen und welcher Container zu welcher gehört,
steht im Monitoring Tool. Ein dort neu eingetragener Dienst ist sofort ansprechbar.

## Entwicklung

```bash
./mvnw test                 # alle Tests, inkl. MCP-Durchlauf gegen Stubs beider Gegenstellen
./mvnw spring-boot:run      # lokal, HA_BASE_URL und HA_TOKEN als Umgebungsvariablen
```

### Aufbau

```
tools/homeassistant/   Werkzeuge, REST-Client, Entitäten-Index und Konfiguration dieser Integration
tools/monitoring/      Werkzeuge, REST-Client, Namensauflösung und Textaufbereitung fürs Monitoring Tool
security/              Bearer-Token-Prüfung vor dem MCP-Endpunkt
common/                Namensnormalisierung, An-/Aus-Vokabular, Zwischenspeicher, Entity-Katalog — von allen Modulen genutzt
```

Jede Integration liegt in einem eigenen Paket unter `tools/` und bringt Werkzeuge, Client und
Konfiguration selbst mit. Eine weitere Integration (Obsidian, Dateien) kommt als zusätzliches Paket
dazu — an den bestehenden ist dafür nichts zu ändern.

Damit das auch stimmt, gibt ein Modul nach außen **nur seine eigenen Typen** als Bean heraus:
`HttpClient` und `RestClient` entstehen modul-intern. Sonst gäbe es sie zweimal im Kontext, jede
Einspritzung nach Typ wäre mehrdeutig, und ein neues Modul würde ein bestehendes brechen — genau das
ist beim zweiten Modul passiert.

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
- **Monitoring Tool** — überwacht diesen Dienst und ist zugleich die Gegenstelle des Moduls
  `tools/monitoring` (dessen Schnittstelle `/api/integration/v1`)
