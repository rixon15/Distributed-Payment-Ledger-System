package org.example.gatewayservice;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.gatewayservice.auth.PhantomTokenResolver;
import org.example.gatewayservice.auth.ResolvedTokenContext;
import org.example.gatewayservice.auth.TokenResolutionRequest;
import org.example.gatewayservice.auth.exception.DpopValidationException;
import org.example.gatewayservice.auth.exception.TokenResolutionException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class PhantomTokenFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final PhantomTokenResolver phantomTokenResolver;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        try {
            String opaqueToken = extractBearerToken(request);
            String dpopProof = request.getHeader("DPoP");

            if (opaqueToken == null || dpopProof == null) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing Authorization or DPoP header");
                return;
            }

            String resolvedJwt = phantomTokenResolver.resolve(new TokenResolutionRequest(
                    opaqueToken, dpopProof, request.getMethod(), request.getRequestURI().toString()
            ));

            ResolvedTokenContext.set(resolvedJwt);
            filterChain.doFilter(request, response);
        } catch (DpopValidationException | TokenResolutionException e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
        } finally {
            ResolvedTokenContext.clear();
        }
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith(BEARER_PREFIX)) return null;

        return header.substring(BEARER_PREFIX.length());
    }
}
