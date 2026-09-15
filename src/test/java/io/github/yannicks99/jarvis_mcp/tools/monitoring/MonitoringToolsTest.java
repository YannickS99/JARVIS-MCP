package io.github.yannicks99.jarvis_mcp.tools.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Die Werkzeuge gegen ein Monitoring Tool aus Pappe - ueber echtes HTTP, damit Token-Header,
 * Einlesen der Antwort und die Behandlung des Fehlerformats mitgeprueft werden.
 */
class MonitoringToolsTest {

    private StubMonitoringTool monitoringTool;
    private MonitoringTools tools;

    @BeforeEach
    void startStub() throws IOException {
        monitoringTool = new StubMonitoringTool();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(monitoringTool.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer geheim")
                .build();
        tools = new MonitoringTools(new MonitoringClient(restClient));
    }

    @AfterEach
    void stopStub() {
        monitoringTool.close();
    }

    @Test
    @DisplayName("die Statusabfrage ohne Namen beantwortet \"ist irgendwas ausgefallen?\"")
    void reportsOverview() {
        monitoringTool.status(StubMonitoringTool.status(3, 1, 1, 0, 0, 1,
                StubMonitoringTool.application(1L, "Monetheus", "UP", "HTTP 200", true,
                        "monetheus-backend", "running"),
                StubMonitoringTool.application(2L, "FilmPickr", "DOWN", "Verbindung abgelehnt", true,
                        "filmpickr-backend", "exited"),
                StubMonitoringTool.application(3L, "Odysseus", "INACTIVE",
                        "Ueber das Monitoring Tool gestoppt", true, "odysseus", "exited")));

        assertThat(tools.getApplicationsStatus(null))
                .contains("Von 3 Anwendungen laeuft 1.")
                .contains("Ausgefallen: FilmPickr.")
                .contains("Absichtlich gestoppt: Odysseus.");
    }

    @Test
    @DisplayName("mit Namen kommt nur diese Anwendung - schreibweisentolerant")
    void reportsSingleApplication() {
        monitoringTool.status(oneUpOneDown());

        assertThat(tools.getApplicationsStatus("filmpickr"))
                .contains("'FilmPickr' ist ausgefallen")
                .contains("Verbindung abgelehnt");
    }

    @Test
    @DisplayName("ein unbekannter Name liefert die vorhandenen Namen, damit die KI es erneut versuchen kann")
    void unknownNameIsTextNotFailure() {
        monitoringTool.status(oneUpOneDown());

        assertThat(tools.getApplicationsStatus("Odysseus"))
                .contains("keine Anwendung namens 'Odysseus'")
                .contains("Monetheus, FilmPickr");
    }

    @Test
    @DisplayName("stoppen ruft die Stopp-Aktion mit Token auf und meldet den neuen Zustand")
    void stopsApplication() {
        monitoringTool.status(oneUpOneDown());

        String answer = tools.setApplicationPower("Monetheus", "aus");

        assertThat(monitoringTool.actions()).singleElement().satisfies(action -> {
            assertThat(action.applicationId()).isEqualTo(1L);
            assertThat(action.action()).isEqualTo("stop");
            assertThat(action.token()).isEqualTo("Bearer geheim");
        });
        assertThat(answer).contains("wurde gestoppt").contains("absichtlich gestoppt");
    }

    @Test
    @DisplayName("starten ruft die Start-Aktion auf")
    void startsApplication() {
        monitoringTool.status(oneUpOneDown());

        String answer = tools.setApplicationPower("FilmPickr", "an");

        assertThat(monitoringTool.actions()).singleElement()
                .satisfies(action -> assertThat(action.action()).isEqualTo("start"));
        assertThat(answer).contains("wurde gestartet");
    }

    @Test
    @DisplayName("ist die Anwendung schon im gewuenschten Zustand, wird nichts geschaltet")
    void doesNotSwitchWhenAlreadyInDesiredState() {
        monitoringTool.status(oneUpOneDown());

        assertThat(tools.setApplicationPower("Monetheus", "on"))
                .isEqualTo("Die Anwendung 'Monetheus' laeuft bereits.");
        assertThat(monitoringTool.actions()).isEmpty();
    }

    @Test
    @DisplayName("ohne zugeordneten Container wird gar nicht erst geschaltet")
    void refusesApplicationWithoutContainer() {
        monitoringTool.status(StubMonitoringTool.status(1, 0, 0, 0, 1, 0,
                StubMonitoringTool.application(7L, "Samba", "UNKNOWN", "Ueberwachung deaktiviert",
                        false, null, null)));

        assertThat(tools.setApplicationPower("Samba", "off"))
                .contains("kein Docker-Container zugeordnet");
        assertThat(monitoringTool.actions()).isEmpty();
    }

    @Test
    @DisplayName("ein geschuetzter Container ergibt den Grund als Text, keinen Protokollfehler")
    void passesOnRefusalReason() {
        monitoringTool.status(oneUpOneDown());
        monitoringTool.actionResponse(403, StubMonitoringTool.problem(403, "Aktion nicht erlaubt",
                "Container 'monetheus-backend' ist vor Eingriffen geschuetzt"));

        assertThat(tools.setApplicationPower("Monetheus", "off"))
                .contains("konnte nicht gestoppt werden")
                .contains("vor Eingriffen geschuetzt");
    }

    @Test
    @DisplayName("nicht erreichbares Docker ergibt ebenfalls eine Auskunft statt einer Stoerung")
    void passesOnDockerUnavailable() {
        monitoringTool.status(oneUpOneDown());
        monitoringTool.actionResponse(503, StubMonitoringTool.problem(503, "Docker nicht erreichbar",
                "Die Docker Engine ist nicht erreichbar"));

        assertThat(tools.setApplicationPower("Monetheus", "off"))
                .contains("Die Docker Engine ist nicht erreichbar");
    }

    @Test
    @DisplayName("ein abgelehntes Token ist eine Stoerung und kein Ergebnis fuer die KI")
    void rejectedTokenFails() {
        monitoringTool.statusCode(401);

        assertThatThrownBy(() -> tools.getApplicationsStatus(null))
                .isInstanceOf(MonitoringException.class)
                .isNotInstanceOf(MonitoringRejectedException.class)
                .hasMessageContaining("das Token wurde abgelehnt");
    }

    @Test
    @DisplayName("eine abgeschaltete Integrationsschnittstelle wird als solche gemeldet")
    void disabledInterfaceFails() {
        monitoringTool.statusCode(404);

        assertThatThrownBy(() -> tools.getApplicationsStatus(null))
                .isInstanceOf(MonitoringException.class)
                .hasMessageContaining("nicht freigeschaltet");
    }

    @Test
    @DisplayName("ein unbekannter Zustand wird zu \"unklar\" statt zu einem Einlesefehler")
    void toleratesUnknownState() {
        monitoringTool.status(StubMonitoringTool.status(1, 0, 0, 0, 1, 0,
                StubMonitoringTool.application(1L, "Monetheus", "MAINTENANCE", "Wartung", true,
                        "monetheus-backend", "running")));

        assertThat(tools.getApplicationsStatus("Monetheus")).contains("ist unklar");
    }

    @Test
    @DisplayName("ein ungueltiger Schaltzustand wird nicht geraten")
    void rejectsInvalidPower() {
        assertThat(tools.setApplicationPower("Monetheus", "heller"))
                .contains("kein gueltiger Schaltzustand");
        assertThat(monitoringTool.actions()).isEmpty();
    }

    private static String oneUpOneDown() {
        return StubMonitoringTool.status(2, 1, 1, 0, 0, 0,
                StubMonitoringTool.application(1L, "Monetheus", "UP", "HTTP 200", true,
                        "monetheus-backend", "running"),
                StubMonitoringTool.application(2L, "FilmPickr", "DOWN", "Verbindung abgelehnt", true,
                        "filmpickr-backend", "exited"));
    }
}
