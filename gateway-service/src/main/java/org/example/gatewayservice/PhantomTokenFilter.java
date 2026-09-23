package org.example.gatewayservice;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gatewayservice.auth.PhantomTokenResolver;
import org.example.gatewayservice.auth.ResolvedTokenContext;
import org.example.gatewayservice.auth.TokenResolutionRequest;
import org.example.gatewayservice.auth.exception.DpopValidationException;
import org.example.gatewayservice.auth.exception.TokenResolutionException;
import org.example.gatewayservice.core.config.GatewayProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;

@Slf4j
@Component
@RequiredArgsConstructor
public class PhantomTokenFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String DPOP_HEADER = "DPoP";

    private final PhantomTokenResolver phantomTokenResolver;
    private final GatewayProperties gatewayProperties;
    private final JsonMapper jsonMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String opaqueToken = extractBearerToken(request);
        String dpopProof = request.getHeader(DPOP_HEADER);

        if (opaqueToken == null || dpopProof == null) {
            writeProblem(request, response, HttpStatus.UNAUTHORIZED,
                    "Missing Authorization or DPoP Header", "DPoP");
            return;
        }

        String resolvedJwt;

        try {
            resolvedJwt = phantomTokenResolver.resolve(new TokenResolutionRequest(
                    opaqueToken, dpopProof, request.getMethod(),
                    gatewayProperties.publicBaseUrl() + request.getRequestURI()));
        } catch (DpopValidationException e) {
            log.debug("Rejected DPoP proof: {}", e.getMessage());
            writeProblem(request, response, HttpStatus.UNAUTHORIZED, "Invalid DPoP proof", "DPoP error=\"invalid_dpop_proof\"");
            return;
        } catch (TokenResolutionException e) {
            log.debug("Rejected access token: {}", e.getMessage());
            writeProblem(request, response, HttpStatus.UNAUTHORIZED, "Invalid access token", "DPoP error=\"invalid_token\"");
            return;
        } catch (StatusRuntimeException e) {
            log.warn("Authorization service call failed with {}", e.getStatus());
            writeProblem(request, response, authServiceFailureStatus(e.getStatus().getCode()), null, null);
            return;
        }

        try {
            ResolvedTokenContext.set(resolvedJwt);
            filterChain.doFilter(request, response);
        } finally {
            ResolvedTokenContext.clear();
        }
    }

    /**
     * A failing authorization-service call is the gateway's upstream problem, never a problem with the client's
     * credentials, so it must not surface as 401 regardless of the status authorization-service returned.
     */
    private static HttpStatus authServiceFailureStatus(Status.Code code) {
        return switch (code) {
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }

    private void writeProblem(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                              String detail, String wwwAuthenticate) throws IOException {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        if (wwwAuthenticate != null) response.setHeader(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate);

        jsonMapper.writeValue(response.getOutputStream(), problem);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith(BEARER_PREFIX)) return null;

        return header.substring(BEARER_PREFIX.length());
    }
}
