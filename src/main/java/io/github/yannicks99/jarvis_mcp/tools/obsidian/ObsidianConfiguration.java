package io.github.yannicks99.jarvis_mcp.tools.obsidian;

import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verdrahtet das Obsidian-Modul. Alles, was es braucht, entsteht hier - kein anderes Paket muss
 * angefasst werden.
 *
 * <p>Deutlich weniger als bei den anderen Modulen: kein HTTP-Client, kein Zwischenspeicher, kein
 * Index. Der Vault ist ein Ordner auf demselben Rechner, und was dort steht, soll immer frisch
 * gelesen werden - eine Notiz, die gerade in Obsidian geaendert wurde, darf nicht veraltet in
 * einem Gespraech auftauchen.
 *
 * <p><strong>Abgeschaltet als Vorgabe</strong> ({@code jarvis-mcp.obsidian.enabled=false}): Ohne
 * eingehaengten Ordner wuerde jedes Werkzeug scheitern, und die KI soll Werkzeuge gar nicht erst
 * sehen, die es auf dieser Maschine nicht gibt.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "jarvis-mcp.obsidian", name = "enabled", havingValue = "true")
public class ObsidianConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ObsidianConfiguration.class);

    @Bean
    Vault vault(ObsidianProperties properties) {
        if (!properties.usable()) {
            log.warn("Obsidian-Modul ist aktiv, aber {} ist kein Ordner - die Werkzeuge werden bei "
                            + "jedem Aufruf scheitern. Ist der Vault-Ordner in den Container eingehaengt?",
                    properties.root());
        } else if (properties.writable() && !Files.isWritable(properties.writeRoot())) {
            // Der haeufigste Fall: Der Container laeuft unter einer anderen Kennung als der, der die
            // Dateien gehoeren (im Vault auf JARVIS ist das 1000:1000).
            log.warn("Obsidian-Modul darf schreiben, aber {} ist fuer diesen Benutzer nicht "
                            + "beschreibbar - in der docker-compose.yml user: \"1000:1000\" setzen "
                            + "und pruefen, ob der Ordner beschreibbar eingehaengt ist.",
                    properties.writeRoot());
        } else {
            log.info("Obsidian-Modul aktiv: liest {}, schreibt {}{}",
                    properties.root(),
                    properties.writable() ? properties.writeRoot() : "nichts",
                    properties.deniedFolders().isEmpty()
                            ? ""
                            : " (gesperrt: " + String.join(", ", properties.deniedFolders()) + ")");
        }
        return new Vault(properties);
    }

    @Bean
    ObsidianTools obsidianTools(Vault vault) {
        return new ObsidianTools(vault);
    }

    /**
     * Die schreibenden Werkzeuge entstehen nur, wenn sie erlaubt sind - so taucht bei
     * {@code writable=false} keines davon in {@code tools/list} auf, statt bei jedem Aufruf
     * abzulehnen.
     */
    @Bean
    @ConditionalOnProperty(prefix = "jarvis-mcp.obsidian", name = "writable", havingValue = "true",
            matchIfMissing = true)
    ObsidianWriteTools obsidianWriteTools(Vault vault) {
        return new ObsidianWriteTools(vault);
    }
}
