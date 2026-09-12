package io.github.yannicks99.jarvis_mcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Laesst nur Anfragen mit dem vereinbarten Bearer-Token an den MCP-Endpunkt durch.
 *
 * <p>Bewusst ein schlanker Servlet-Filter statt Spring Security: Es gibt genau eine Regel und
 * genau einen Endpunkt. Spring Security braechte eine Filterkette samt Autokonfiguration mit, ohne
 * dass davon hier irgendetwas gebraucht wuerde - das kostet Startzeit und verdeckt die eine Regel,
 * um die es geht.
 */
public class BearerTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    /**
     * Vorab kodiert: Der Vergleich laeuft pro Anfrage, das Umwandeln in Bytes nicht.
     */
    private final byte[] expectedToken;

    public BearerTokenFilter(String expectedToken) {
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(PREFIX) || !matches(header.substring(PREFIX.length()))) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * {@link MessageDigest#isEqual} vergleicht ueber die volle Laenge und verraet damit nicht ueber
     * die Antwortzeit, wie viele Zeichen des Tokens bereits stimmen.
     */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(presented.strip().getBytes(StandardCharsets.UTF_8), expectedToken);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        // Weist den Aufrufer auf das erwartete Verfahren hin, ohne etwas ueber das Token zu sagen.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"missing or invalid bearer token\"}");
    }
}
