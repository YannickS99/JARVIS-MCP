package io.github.yannicks99.jarvis_mcp.tools.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prueft das, was am Ende beim Sprachmodell ankommt - ohne Monitoring Tool und ohne HTTP. Genau dafuer
 * liegt die Textaufbereitung in einer eigenen Klasse.
 */
class StatusReportTest {

    @Test
    @DisplayName("laeuft alles, bleibt es bei einem Satz ohne Aufzaehlung")
    void reportsAllUpBriefly() {
        MonitoringStatus status = new MonitoringStatus(
                new MonitoringStatus.Summary(3, 3, 0, 0, 0, 0),
                List.of(application(1L, "Monetheus", ApplicationState.UP),
                        application(2L, "FilmPickr", ApplicationState.UP),
                        application(3L, "Filebrowser", ApplicationState.UP)));

        assertThat(StatusReport.overview(status)).isEqualTo("Alle 3 Anwendungen laufen.");
    }

    @Test
    @DisplayName("Ausfaelle werden beim Namen genannt, gestoppte Dienste getrennt davon")
    void namesFailuresAndStoppedServicesSeparately() {
        MonitoringStatus status = new MonitoringStatus(
                new MonitoringStatus.Summary(5, 2, 1, 1, 0, 1),
                List.of(application(1L, "Monetheus", ApplicationState.UP),
                        application(2L, "FilmPickr", ApplicationState.DOWN),
                        application(3L, "Filebrowser", ApplicationState.DEGRADED),
                        application(4L, "Odysseus", ApplicationState.INACTIVE),
                        application(5L, "ntfy", ApplicationState.UP)));

        String text = StatusReport.overview(status);

        assertThat(text)
                .contains("Von 5 Anwendungen laufen 2.")
                .contains("Ausgefallen: FilmPickr.")
                .contains("Gestoert: Filebrowser.")
                .contains("Absichtlich gestoppt: Odysseus.");
        // Ein bewusst gestoppter Dienst darf nicht als Ausfall durchgehen.
        assertThat(text.indexOf("Ausgefallen")).isLessThan(text.indexOf("Absichtlich gestoppt"));
    }

    @Test
    @DisplayName("ohne hinterlegte Anwendungen wird das gesagt statt \"alles laeuft\"")
    void reportsEmptyInventory() {
        MonitoringStatus status = new MonitoringStatus(
                new MonitoringStatus.Summary(0, 0, 0, 0, 0, 0), List.of());

        assertThat(StatusReport.overview(status))
                .isEqualTo("Im Monitoring Tool ist keine Anwendung hinterlegt.");
    }

    @Test
    @DisplayName("die Einzelansicht nennt Zustand, Begruendung und den Containerzustand")
    void describesSingleApplication() {
        MonitoredApplication application = new MonitoredApplication(1L, "Monetheus",
                ApplicationState.DOWN, "Verbindung abgelehnt", true, "monetheus-backend", "exited");

        assertThat(StatusReport.single(application))
                .isEqualTo("Die Anwendung 'Monetheus' ist ausgefallen (Verbindung abgelehnt). "
                        + "Der Container 'monetheus-backend' laeuft nicht.");
    }

    @Test
    @DisplayName("eine Anwendung ohne Ueberwachung sagt, dass ihr Zustand nichts aussagt")
    void warnsAboutUnmonitoredApplication() {
        MonitoredApplication application = new MonitoredApplication(1L, "Samba",
                ApplicationState.UNKNOWN, "Ueberwachung deaktiviert", false, null, null);

        assertThat(StatusReport.single(application))
                .contains("ist unklar")
                .contains("keine Ueberwachung eingerichtet")
                // Ohne zugeordneten Container wird ueber den Container nichts behauptet.
                .doesNotContain("Container");
    }

    @Test
    @DisplayName("nach einem Eingriff wird der neue Zustand mitgemeldet")
    void reportsStateAfterSwitching() {
        MonitoredApplication application = new MonitoredApplication(1L, "Monetheus",
                ApplicationState.INACTIVE, "Ueber das Monitoring Tool gestoppt", true,
                "monetheus-backend", "exited");

        assertThat(StatusReport.afterSwitching(application, "gestoppt"))
                .isEqualTo("Die Anwendung 'Monetheus' wurde gestoppt. "
                        + "Sie ist jetzt absichtlich gestoppt.");
    }

    private static MonitoredApplication application(long id, String name, ApplicationState state) {
        return new MonitoredApplication(id, name, state, "Grund", true, "container-" + id,
                state == ApplicationState.UP ? "running" : "exited");
    }
}
