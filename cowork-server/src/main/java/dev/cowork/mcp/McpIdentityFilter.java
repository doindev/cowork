package dev.cowork.mcp;

import java.io.IOException;
import java.util.Optional;

import dev.cowork.conversation.McpTokenService;
import dev.cowork.conversation.Participant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates MCP requests with the per-turn bearer token and binds the calling
 * participant to the request thread.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpIdentityFilter extends OncePerRequestFilter {

    private final McpTokenService tokens;

    public McpIdentityFilter(McpTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/mcp");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        Optional<Participant> participant = tokens.resolve(token);
        if (participant.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid MCP bearer token");
            return;
        }
        McpCallerContext.set(participant.get());
        try {
            chain.doFilter(request, response);
        } finally {
            McpCallerContext.clear();
        }
    }
}
