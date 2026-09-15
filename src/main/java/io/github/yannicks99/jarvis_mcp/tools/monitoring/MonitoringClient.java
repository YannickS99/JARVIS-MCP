package io.github.yannicks99.jarvis_mcp.tools.monitoring;

import io.github.yannicks99.jarvis_mcp.common.Power;
import java.util.Set;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Schmaler REST-Client gegen die Integrationsschnittstelle des Monitoring Tools. Kennt nur die beiden
 * Aufrufe, die das Tool-Modul braucht - Zustand lesen und einen Container schalten.
 *
 * <p>Die Fachlogik bleibt dort: JARVIS-MCP baut die Health-Checks und die Docker-Steuerung nicht nach,
 * es ruft sie auf (Kernprinzip "Adapter, nicht Neuimplementierung").
 */
public class MonitoringClient {

    private static final String BASE = "/api/integration/v1";

    /**
     * Statuscodes, hinter denen ein fachlicher Grund stehen kann, den die KI weitergeben soll:
     * ungueltige Anfrage (kein Container zugeordnet), verboten (geschuetzter Container), nicht
     * gefunden (Anwendung gelöscht) und Dienst nicht verfuegbar (Docker Engine weg).
     *
     * <p>401 steht bewusst nicht dabei: Ein abgelehntes Token ist ein Konfigurationsfehler im Haus und
     * keine Auskunft fuer den Nutzer.
     */
    private static final Set<Integer> EXPLAINED_BY_TOOL = Set.of(400, 403, 404, 409, 503);

    private final RestClient restClient;

    public MonitoringClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** Der zuletzt ermittelte Zustand aller hinterlegten Anwendungen. */
    public MonitoringStatus status() {
        return call("Zustand abfragen", () -> restClient.get()
                .uri(BASE + "/status")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(MonitoringStatus.class));
    }

    /**
     * Schaltet den Container einer Anwendung.
     *
     * @param id    Kennung der Anwendung, aus einer vorherigen Statusabfrage
     * @param power {@link Power#ON} startet, {@link Power#OFF} stoppt
     * @return die Anwendung in ihrem neuen Zustand
     */
    public MonitoredApplication setPower(long id, Power power) {
        String path = BASE + "/applications/" + id + (power == Power.ON ? "/start" : "/stop");
        return call("Container schalten", () -> restClient.post()
                .uri(path)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(MonitoredApplication.class));
    }

    private <T> T call(String what, java.util.function.Supplier<T> request) {
        T body;
        try {
            body = request.get();
        } catch (RestClientResponseException ex) {
            throw translate(what, ex);
        } catch (RestClientException ex) {
            throw new MonitoringException("Monitoring Tool: '%s' fehlgeschlagen - nicht erreichbar (%s)"
                    .formatted(what, ex.getMessage()), ex);
        }
        if (body == null) {
            throw new MonitoringException(
                    "Das Monitoring Tool hat auf '" + what + "' eine leere Antwort geliefert");
        }
        return body;
    }

    /**
     * Uebersetzt eine Fehlerantwort in die passende der beiden Ausnahmen.
     *
     * <p>Unterschieden wird am Vorhandensein einer Problembeschreibung nach RFC 9457: Die liefert das
     * Monitoring Tool fuer fachliche Ablehnungen, samt deutschem Grund. Fehlt sie, stammt die Antwort
     * nicht aus der Fachschicht - so antwortet etwa der Token-Filter -, und dann ist es eine Stoerung.
     */
    private static MonitoringException translate(String what, RestClientResponseException ex) {
        if (EXPLAINED_BY_TOOL.contains(ex.getStatusCode().value())) {
            String reason = detailOf(ex);
            if (reason != null) {
                return new MonitoringRejectedException(reason);
            }
        }
        return new MonitoringException("Monitoring Tool: '%s' fehlgeschlagen - %s (%s)"
                .formatted(what, hintFor(ex.getStatusCode()), ex.getMessage()), ex);
    }

    private static String detailOf(RestClientResponseException ex) {
        try {
            ProblemDetail problem = ex.getResponseBodyAs(ProblemDetail.class);
            if (problem != null && problem.getDetail() != null && !problem.getDetail().isBlank()) {
                return problem.getDetail();
            }
        } catch (RuntimeException ignored) {
            // Keine Problembeschreibung - dann eben als Stoerung behandeln.
        }
        return null;
    }

    /**
     * Die beiden Statuscodes, die hier eine feste Bedeutung haben, beim Namen nennen: Das Token stimmt
     * nicht, bzw. die Integrationsschnittstelle ist dort gar nicht freigeschaltet. Beides ist ein
     * Konfigurationsfehler im Haus und soll als solcher im Protokoll stehen.
     */
    private static String hintFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> "das Token wurde abgelehnt (jarvis-mcp.monitoring.token gegen "
                    + "MONITORING_INTEGRATION_TOKEN pruefen)";
            case 404 -> "die Integrationsschnittstelle ist dort nicht freigeschaltet "
                    + "(MONITORING_INTEGRATION_TOKEN im Monitoring Tool setzen)";
            default -> "das Monitoring Tool antwortete mit " + status;
        };
    }
}
