package io.github.yannicks99.jarvis_mcp.tools.monitoring;

import java.net.http.HttpClient;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Verdrahtet das Monitoring-Tool-Modul. Alles, was dieses Modul braucht, entsteht hier - kein anderes
 * Paket muss angefasst werden.
 *
 * <p>Deutlich weniger als beim Home-Assistant-Modul: kein Index, kein Zwischenspeicher, kein
 * Warmhalten. Der Grund ist, dass hier nichts aufgeloest werden muss, was teuer zu beschaffen waere -
 * Namen und Zustaende kommen in derselben schlanken Antwort, und Zustaende darf man ohnehin nicht
 * zwischenspeichern.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "jarvis-mcp.monitoring", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class MonitoringConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MonitoringConfiguration.class);

    @Bean
    MonitoringClient monitoringClient(MonitoringProperties properties) {
        return new MonitoringClient(restClient(properties));
    }

    /**
     * Der HTTP-Unterbau dieses Moduls entsteht hier und wird bewusst <em>nicht</em> als Bean
     * veroeffentlicht - aus demselben Grund wie im Home-Assistant-Modul: {@link HttpClient} und
     * {@link RestClient} sind allgemeine Typen, und zwei Beans desselben Typs machen jede
     * Einspritzung nach Typ mehrdeutig. Nach aussen gibt dieses Modul nur {@link MonitoringClient}
     * und seine Werkzeuge heraus.
     */
    private static RestClient restClient(MonitoringProperties properties) {
        if (!properties.configured()) {
            log.warn("Monitoring-Tool-Modul ist aktiv, aber jarvis-mcp.monitoring.base-url bzw. .token "
                    + "fehlen - die Werkzeuge werden bei jedem Aufruf scheitern. Das Token muss mit "
                    + "MONITORING_INTEGRATION_TOKEN im Monitoring Tool uebereinstimmen.");
        }

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient(properties));
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                .build();
    }

    /**
     * Ein eigener HTTP-Client, getrennt von dem des Home-Assistant-Moduls: Die beiden haben eigene
     * Zeitlimits, und ein traeges Home Assistant soll die Verbindungen zum Monitoring Tool nicht
     * mitbelegen. Er haelt die Verbindung offen, sodass nachfolgende Aufrufe keinen erneuten
     * TCP-Handshake bezahlen.
     */
    private static HttpClient httpClient(MonitoringProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                // Virtuelle Threads: Der Client braucht keinen eigenen Pool-Unterbau, und ein
                // wartender Aufruf belegt keinen Betriebssystem-Thread.
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Bean
    MonitoringTools monitoringTools(MonitoringClient monitoringClient) {
        return new MonitoringTools(monitoringClient);
    }

    @Bean
    MonitoringResources monitoringResources(MonitoringClient monitoringClient, ObjectMapper jsonMapper) {
        return new MonitoringResources(monitoringClient, jsonMapper);
    }
}
