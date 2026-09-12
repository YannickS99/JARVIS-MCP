package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Verdrahtet das Home-Assistant-Modul. Alles, was dieses Modul braucht, entsteht hier - kein
 * anderes Paket muss angefasst werden, wenn eine weitere Integration dazukommt.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "jarvis-mcp.home-assistant", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class HomeAssistantConfiguration {

    private static final Logger log = LoggerFactory.getLogger(HomeAssistantConfiguration.class);

    /** Nur diese Domains werden in den Index aufgenommen - alles andere braucht kein Werkzeug. */
    private static final List<String> INDEXED_DOMAINS = List.of("light.", "script.", "scene.");

    /**
     * Ein einziger HTTP-Client fuer alle Aufrufe: Er haelt die Verbindung zu Home Assistant offen,
     * sodass nachfolgende Aufrufe weder TCP- noch TLS-Handshake erneut bezahlen. Genau das macht
     * den Unterschied zwischen einer Lichtschaltung, die sofort sitzt, und einer, die man hoert.
     */
    @Bean
    HttpClient homeAssistantHttpClient(HomeAssistantProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                // Virtuelle Threads: Der Client braucht keinen eigenen Pool-Unterbau, und ein
                // wartender Aufruf belegt keinen Betriebssystem-Thread.
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Bean
    RestClient homeAssistantRestClient(HttpClient httpClient, HomeAssistantProperties properties) {
        if (!properties.configured()) {
            log.warn("Home-Assistant-Modul ist aktiv, aber jarvis-mcp.home-assistant.base-url bzw. "
                    + ".token fehlen - die Werkzeuge werden bei jedem Aufruf scheitern.");
        }

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(properties.baseUrl())
                // Home Assistant verlangt den Bearer-Header ausnahmslos bei jedem REST-Aufruf.
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                .build();
    }

    @Bean
    HomeAssistantClient homeAssistantClient(RestClient restClient, ObjectMapper jsonMapper) {
        return new HomeAssistantClient(restClient, jsonMapper);
    }

    @Bean
    HomeAssistantEntityIndex homeAssistantEntityIndex(HomeAssistantClient client,
            HomeAssistantProperties properties) {
        return new HomeAssistantEntityIndex(client, INDEXED_DOMAINS,
                properties.cacheTtl(), properties.minRefreshInterval());
    }

    @Bean
    AreaResolver areaResolver(HomeAssistantProperties properties) {
        AreaResolver resolver = new AreaResolver(properties.areas());
        log.info("Home-Assistant-Bereiche konfiguriert: {}",
                resolver.knownNames().isEmpty() ? "(keine)" : String.join(", ", resolver.knownNames()));
        return resolver;
    }

    @Bean
    HomeAssistantTools homeAssistantTools(HomeAssistantClient client, HomeAssistantEntityIndex index,
            AreaResolver areas) {
        return new HomeAssistantTools(client, index, areas);
    }

    /**
     * Haelt den Entitaeten-Index warm.
     *
     * <p>Der Aufbau laeuft damit immer vor dem ersten Werkzeugaufruf und nicht waehrend eines
     * Werkzeugaufrufs - der Nutzer wartet nie auf {@code /api/states}. Ein erster Lauf direkt nach
     * dem Start uebernimmt das Aufwaermen; er darf scheitern, falls Home Assistant spaeter
     * hochkommt als JARVIS-MCP, denn der naechste Takt holt es nach.
     */
    @Bean
    IndexWarmer indexWarmer(HomeAssistantEntityIndex index) {
        return new IndexWarmer(index);
    }

    static class IndexWarmer {

        private final HomeAssistantEntityIndex index;

        IndexWarmer(HomeAssistantEntityIndex index) {
            this.index = index;
        }

        @EventListener(ApplicationReadyEvent.class)
        void warmUp() {
            // In einem eigenen (virtuellen) Thread, damit ein nicht erreichbares Home Assistant den
            // Start nicht um das Verbindungs-Zeitlimit verzoegert.
            Thread.startVirtualThread(index::refreshQuietly);
        }

        @Scheduled(fixedDelayString = "${jarvis-mcp.home-assistant.cache-ttl:60s}",
                initialDelayString = "${jarvis-mcp.home-assistant.cache-ttl:60s}")
        void refresh() {
            index.refreshQuietly();
        }
    }
}
