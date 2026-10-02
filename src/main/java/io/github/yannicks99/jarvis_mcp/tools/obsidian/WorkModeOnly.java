package io.github.yannicks99.jarvis_mcp.tools.obsidian;

import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.context.MetaProvider;

/**
 * Ordnet ein Werkzeug dem Arbeitsmodus des JARVIS-AIService zu - ueber {@code _meta}, den Platz,
 * den MCP fuer solche Absprachen zwischen Server und Client vorsieht.
 *
 * <p>Der AIService bietet ein Werkzeug mit {@code "jarvis/modes": ["work"]} nur in
 * Arbeits-Unterhaltungen an. Die Vault-Werkzeuge gehoeren zum Arbeitsgespraech; im Alltagsmodus
 * machten sie das Tool-Set nur groesser, was ein kleines lokales Modell beim Tool-Calling
 * unzuverlaessiger macht. Die Entscheidung liegt bewusst hier: Der AIService kennt keine
 * Werkzeugnamen, ein neues Werkzeug braucht dort keine Aenderung.
 *
 * <p>Wird per Reflexion angelegt ({@code @McpTool(metaProvider = ...)}) - deshalb oeffentlich und
 * mit parameterlosem Konstruktor.
 */
public class WorkModeOnly implements MetaProvider {

    /** Der Schluessel, den der AIService liest ({@code tools.mcp_provider.MODES_META_KEY}). */
    public static final String MODES_KEY = "jarvis/modes";

    @Override
    public Map<String, Object> getMeta() {
        return Map.of(MODES_KEY, List.of("work"));
    }
}
