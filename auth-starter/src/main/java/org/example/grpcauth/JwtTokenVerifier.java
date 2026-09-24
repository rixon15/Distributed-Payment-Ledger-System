package org.example.grpcauth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimNames;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.example.grpcauth.exception.InvalidTokenException;
import org.example.grpcauth.exception.JwksUnavailableException;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Verifies internal access tokens (RFC 9068 JWT profile) and turns them into an {@link AuthenticatedPrincipal}.
 *
 * <p>Checks, in the order Nimbus applies them:
 * <ol>
 *     <li>the 'typ' header is the configured token type ('at+jwt', or the equivalent 'application/at+jwt');</li>
 *     <li>the algorithm is allowlisted and the signature verifies against a key from the JWK Set, so unsigned
 *     ('none') and HMAC tokens never reach a key;</li>
 *     <li>'iss' matches exactly, 'aud' contains this service, and 'sub', 'exp', 'iat' and 'jti' are present;</li>
 *     <li>'exp' and 'nbf' hold, with clock skew;</li>
 *     <li>'iat' is neither in the future nor older than max-token-age, with clock skew.</li>
 * </ol>
 *
 * <p>Instances are thread-safe.
 */
public class JwtTokenVerifier {

    private static final String SCOPE_CLAIM = "scope";
    private static final Set<String> REQUIRED_CLAIMS = Set.of(
            JWTClaimNames.SUBJECT, JWTClaimNames.EXPIRATION_TIME, JWTClaimNames.ISSUED_AT, JWTClaimNames.JWT_ID);

    private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

    public JwtTokenVerifier(GrpcJwtAuthProperties properties, JWKSource<SecurityContext> keySource) {
        this(properties, keySource, Clock.systemUTC());
    }


    JwtTokenVerifier(GrpcJwtAuthProperties properties, JWKSource<SecurityContext> keySource, Clock clock) {
        String tokenType = properties.tokenType();

        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(
                new JOSEObjectType(tokenType), new JOSEObjectType("application/" + tokenType)));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(properties.jwsAlgorithms(), keySource));
        processor.setJWTClaimsSetVerifier(new InternalTokenClaimsVerifier(properties, clock));
    }

    /**
     * @param token the compact-serialized JWT, without the 'Bearer ' prefix
     * @return the verified caller
     * @throws InvalidTokenException    if any check fails; the message names the check, for logs only
     * @throws JwksUnavailableException if no signing keys can be obtained to check the signature against
     */
    public AuthenticatedPrincipal verify(String token) throws InvalidTokenException, JwksUnavailableException {
        JWTClaimsSet claims;

        try {
            claims = processor.process(token, null);
        } catch (RateLimitReachedException e) {
            // Only reached for a 'kid' missing from a freshly fetched set: the key is unknown, not the endpoint down
            throw new InvalidTokenException("Unknown signing key: JWK Set refetch is rate-limited", e);
        } catch (KeySourceException e) {
            throw new JwksUnavailableException(e);
        } catch (ParseException | BadJOSEException | JOSEException e) {
            throw new InvalidTokenException(e.getMessage(), e);
        }

        return toPrincipal(claims);
    }

    private static AuthenticatedPrincipal toPrincipal(JWTClaimsSet claims) throws InvalidTokenException {
        return new AuthenticatedPrincipal(
                requireText(claims.getSubject(), JWTClaimNames.SUBJECT),
                Set.copyOf(claims.getAudience()),
                scopes(claims.getClaim(SCOPE_CLAIM)),
                requireText(claims.getJWTID(), JWTClaimNames.JWT_ID),
                claims.getIssueTime().toInstant(),
                claims.getExpirationTime().toInstant());
    }

    /**
     * RFC 9068 defines 'scope' as a space-delimited string; Spring Authorization Server writes a JSON array.
     */
    private static Set<String> scopes(Object claim) throws InvalidTokenException {
        return switch (claim) {
            case null -> Set.of();
            case String value -> Arrays.stream(value.split(" "))
                    .filter(scope -> !scope.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
            case List<?> values -> {
                Set<String> scopes = new HashSet<>();
                for (Object value : values) {
                    if (!(value instanceof String scope) || scope.isBlank()) {
                        throw new InvalidTokenException("JWT scope array must contain only non-blank strings");
                    }
                    scopes.add(scope);
                }
                yield Set.copyOf(scopes);
            }
            default -> throw new InvalidTokenException("JWT scope claim must be a string or an array of strings");
        };
    }

    private static String requireText(String value, String claim) throws InvalidTokenException {
        if (value == null || value.isBlank())
            throw new InvalidTokenException("JWT " + claim + " claim must not be blank");

        return value;
    }

    /**
     * Nimbus's claim checks plus the 'iat' checks it doesn't do, all on one clock.
     */
    private static final class InternalTokenClaimsVerifier extends DefaultJWTClaimsVerifier<SecurityContext> {

        private final Clock clock;
        private final Duration clockSkew;
        private final Duration maxTokenAge;

        InternalTokenClaimsVerifier(GrpcJwtAuthProperties properties, Clock clock) {
            // Nimbus probes the audience set with contains(null), which Set.of rejects with an NPE

            super(Collections.singleton(properties.audience()),
                    new JWTClaimsSet.Builder().issuer(properties.issuer()).build(),
                    REQUIRED_CLAIMS,
                    null);
            this.clock = clock;
            this.clockSkew = properties.clockSkew();
            this.maxTokenAge = properties.maxTokenAge();
            setMaxClockSkew(Math.toIntExact(clockSkew.toSeconds()));
        }

        @Override
        protected Date currentTime() {
            return Date.from(clock.instant());
        }

        @Override
        public void verify(JWTClaimsSet claimsSet, SecurityContext context) throws BadJWTException {
            super.verify(claimsSet, context);

            Instant now = clock.instant();
            Instant issuedAt = claimsSet.getIssueTime().toInstant();

            if (issuedAt.isAfter(now.plus(clockSkew))) throw new BadJWTException("JWT issued in the future");
            if (issuedAt.plus(maxTokenAge).plus(clockSkew).isBefore(now))
                throw new BadJWTException("JWT older than max token age");
        }
    }
}
