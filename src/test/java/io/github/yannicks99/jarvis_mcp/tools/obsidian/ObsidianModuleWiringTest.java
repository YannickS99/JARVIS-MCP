package io.github.yannicks99.jarvis_mcp.tools.obsidian;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Haelt fest, dass die Obsidian-Werkzeuge genau dann entstehen, wenn sie es sollen.
 *
 * <p>Das ist nicht Kosmetik: Was als Bean existiert, steht in {@code tools/list} und damit im
 * Prompt des Sprachmodells. Ein Werkzeug, das es auf dieser Maschine gar nicht geben kann, waere
 * dort nur eine Einladung zum Scheitern.
 *
 * <p>Ohne vollen Anwendungskontext - geprueft wird die Verdrahtung dieses einen Moduls, nicht der
 * Start des Servers.
 */
class ObsidianModuleWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObsidianTestConfiguration.class))
            .withPropertyValues("jarvis-mcp.obsidian.root=" + System.getProperty("java.io.tmpdir"));

    @Test
    @DisplayName("Ohne eingehaengten Vault gibt es kein einziges Obsidian-Werkzeug")
    void noToolsWhenDisabled() {
        // Vorgabe ist aus - ohne Ordner wuerde jeder Aufruf scheitern.
        runner.run(context -> assertThat(context)
                .doesNotHaveBean(Vault.class)
                .doesNotHaveBean(ObsidianTools.class)
                .doesNotHaveBean(ObsidianWriteTools.class));
    }

    @Test
    @DisplayName("Eingeschaltet gibt es lesende und schreibende Werkzeuge")
    void readAndWriteToolsWhenEnabled() {
        runner.withPropertyValues("jarvis-mcp.obsidian.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(ObsidianTools.class)
                        .hasSingleBean(ObsidianWriteTools.class));
    }

    @Test
    @DisplayName("Nur lesend angebunden taucht kein schreibendes Werkzeug auf")
    void noWriteToolsWhenReadOnly() {
        runner.withPropertyValues("jarvis-mcp.obsidian.enabled=true", "jarvis-mcp.obsidian.writable=false")
                .run(context -> assertThat(context)
                        .hasSingleBean(ObsidianTools.class)
                        // Bei jedem Aufruf abzulehnen waere schlechter, als es gar nicht anzubieten.
                        .doesNotHaveBean(ObsidianWriteTools.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ObsidianProperties.class)
    @Import(ObsidianConfiguration.class)
    static class ObsidianTestConfiguration {
    }
}
