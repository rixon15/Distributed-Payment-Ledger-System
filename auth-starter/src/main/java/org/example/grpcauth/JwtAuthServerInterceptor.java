package org.example.grpcauth;

import io.grpc.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.example.grpcauth.exception.InvalidTokenException;
import org.example.grpcauth.exception.JwksUnavailableException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authenticates every incoming gRPC call with a bearer JWT, except methods configured as public.
 *
 * <p>On success the {@link AuthenticatedPrincipal} is attached to the call's {@link Context} (see
 * {@link GrpcAuthContext}) and the 'authorization' header is removed, so the raw token can't be logged or forwarded
 * by the service. On failure the call is closed before it reaches the service:
 * <ul>
 *     <li>{@code UNAUTHENTICATED} for a missing, malformed or invalid token;</li>
 *     <li>{@code UNAVAILABLE} when no signing keys can be obtained, which is retryable and says nothing about the
 *     token.</li>
 * </ul>
 * The status description is generic in both cases; the reason is logged server-side only.
 *
 * <p>{@link #interceptCall} runs on the server's application executor, not a transport thread, so the blocking JWK
 * Set fetch the verifier may do on a cache miss doesn't stall other calls' I/O.
 */
public class JwtAuthServerInterceptor implements ServerInterceptor {

    private static final Log logger = LogFactory.getLog(JwtAuthServerInterceptor.class);

    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * RFC 6750 b64token after a case-insensitive 'Bearer' scheme (RFC 9110 auth-schemes are case-insensitive).
     */
    private static final Pattern BEARER = Pattern.compile("Bearer +([A-Za-z0-9\\-._~+/]+=*)",
            Pattern.CASE_INSENSITIVE);

    private static final String UNAUTHENTICATED_DESCRIPTION = "Invalid or missing credentials";
    private static final String UNAVAILABLE_DESCRIPTION = "Authentication temporarily unavailable";

    private final JwtTokenVerifier verifier;
    private final Set<String> publicServices = new HashSet<>();
    private final Set<String> publicMethods = new HashSet<>();

    /**
     * @param publicMethods entries validated by {@link GrpcJwtAuthProperties}: 'package.Service/*' or
     *                      'package.Service/Method'
     */
    public JwtAuthServerInterceptor(JwtTokenVerifier verifier, Collection<String> publicMethods) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");

        for (String entry : publicMethods) {
            if (entry.endsWith("/*")) this.publicServices.add(entry.substring(0, entry.length() - 2));
            else this.publicMethods.add(entry);
        }
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        MethodDescriptor<ReqT, RespT> method = call.getMethodDescriptor();

        if (isPublic(method)) return next.startCall(call, headers);

        AuthenticatedPrincipal principal;

        try {
            principal = verifier.verify(bearerToken(headers));
        } catch (InvalidTokenException e) {
            // Callers are the gateway with fresh tokens: a rejection is a bug or an attack, so log it by default
            logger.warn("Rejected call to " + method.getFullMethodName() + ": " + e.getMessage());
            return reject(call, Status.UNAUTHENTICATED.withDescription(UNAUTHENTICATED_DESCRIPTION));
        } catch (JwksUnavailableException e) {
            logger.warn("Could not authenticate call to " + method.getFullMethodName() + ": " + e.getMessage(), e);
            return reject(call, Status.UNAVAILABLE.withDescription(UNAVAILABLE_DESCRIPTION));
        }

        headers.removeAll(AUTHORIZATION);
        Context context = Context.current().withValue(GrpcAuthContext.PRINCIPAL, principal);
        return Contexts.interceptCall(context, call, headers, next);
    }

    private boolean isPublic(MethodDescriptor<?, ?> method) {
        return publicMethods.contains(method.getFullMethodName()) || publicServices.contains(method.getServiceName());
    }

    /**
     * @throws InvalidTokenException if there isn't exactly one 'authorization' header holding a bearer token
     */
    private static String bearerToken(Metadata headers) throws InvalidTokenException {
        Iterable<String> values = headers.getAll(AUTHORIZATION);
        if (values == null) throw new InvalidTokenException("Missing authorization header");

        Iterator<String> iterator = values.iterator();
        String value = iterator.next();

        // Two headers could be read differently by two components; refuse to pick one
        if (iterator.hasNext()) throw new InvalidTokenException("Multiple authorization headers");

        Matcher matcher = BEARER.matcher(value);
        if (!matcher.matches()) throw new InvalidTokenException("Authorization header is not a bearer token");

        return matcher.group(1);
    }

    private static <ReqT, RespT> ServerCall.Listener<ReqT> reject(ServerCall<ReqT, RespT> call, Status status) {
        call.close(status, new Metadata());
        return new ServerCall.Listener<>() {};
    }
}
