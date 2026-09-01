package org.example.gatewayservice.auth;

import com.google.protobuf.Timestamp;
import org.example.gatewayservice.auth.exception.TokenResolutionException;
import org.example.grpc.auth.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PhantomTokenResolverTest {

    private static final String OPAQUE_TOKEN = "opaque-1";
    private static final String DPOP_PROOF = "proof-jwt";
    private static final String HTTP_METHOD = "GET";
    private static final String HTTP_URI = "https://gateway.internal/balance";

    private TokenCacheService tokenCacheService;
    private DpopProofValidator dpopProofValidator;
    private DpopReplayGuard dpopReplayGuard;
    private AuthServiceGrpc.AuthServiceBlockingStub authServiceStub;
    private PhantomTokenResolver resolver;

    @BeforeEach
    void setUp() {
        tokenCacheService = mock(TokenCacheService.class);
        dpopProofValidator = mock(DpopProofValidator.class);
        dpopReplayGuard = mock(DpopReplayGuard.class);
        authServiceStub = mock(AuthServiceGrpc.AuthServiceBlockingStub.class);

        resolver = new PhantomTokenResolver(tokenCacheService, dpopProofValidator, dpopReplayGuard, authServiceStub);
    }

    @Test
    void resolve_usesCachedToken_onCacheHit() {
        CachedToken cached = new CachedToken("jwt-value", "jkt-thumbprint", Instant.now().plusSeconds(60));
        when(tokenCacheService.get(OPAQUE_TOKEN)).thenReturn(Optional.of(cached));
        when(dpopProofValidator.validate(any())).thenReturn("jti-1");

        String result = resolver.resolve(new TokenResolutionRequest(OPAQUE_TOKEN, DPOP_PROOF, HTTP_METHOD, HTTP_URI));

        assertThat(result).isEqualTo("jwt-value");
        verify(dpopReplayGuard).checkAndRecord("jti-1");
        verifyNoInteractions(authServiceStub);
    }

    @Test
    void resolve_exchangesAndCaches_onCacheMiss() {
        when(tokenCacheService.get(OPAQUE_TOKEN)).thenReturn(Optional.empty());
        when(dpopProofValidator.validate(any())).thenReturn("jti-1");

        Instant expiresAt = Instant.now().plusSeconds(45);
        TokenExchangeResponse response = TokenExchangeResponse.newBuilder()
                .setResolved(ResolvedToken.newBuilder()
                        .setAccessToken("fresh-jwt")
                        .setCnfJkt("fresh-jkt")
                        .setExpiresAt(Timestamp.newBuilder().setSeconds(expiresAt.getEpochSecond()).build())
                        .build())
                .build();
        when(authServiceStub.exchangeToken(any())).thenReturn(response);

        String result = resolver.resolve(new TokenResolutionRequest(OPAQUE_TOKEN, DPOP_PROOF, HTTP_METHOD, HTTP_URI));

        assertThat(result).isEqualTo("fresh-jwt");
        verify(tokenCacheService).put(eq(OPAQUE_TOKEN), argThat(token ->
                token.accessToken().equals("fresh-jwt") && token.cnfJkt().equals("fresh-jkt")));
        verify(dpopReplayGuard).checkAndRecord("jti-1");
    }

    @Test
    void resolve_throws_whenExchangeReturnsInvalid() {
        when(tokenCacheService.get(OPAQUE_TOKEN)).thenReturn(Optional.empty());

        TokenExchangeResponse response = TokenExchangeResponse.newBuilder()
                .setInvalid(InvalidToken.newBuilder().setReason(InvalidReason.EXPIRED).build())
                .build();
        when(authServiceStub.exchangeToken(any())).thenReturn(response);

        assertThatThrownBy(() -> resolver.resolve(new TokenResolutionRequest(OPAQUE_TOKEN, DPOP_PROOF, HTTP_METHOD, HTTP_URI)))
                .isInstanceOf(TokenResolutionException.class);

        verify(tokenCacheService, never()).put(any(), any());
        verifyNoInteractions(dpopProofValidator, dpopReplayGuard);
    }

    @Test
    void resolve_propagatesReplayRejection() {
        CachedToken cached = new CachedToken("jwt-value", "jkt-thumbprint", Instant.now().plusSeconds(60));
        when(tokenCacheService.get(OPAQUE_TOKEN)).thenReturn(Optional.of(cached));
        when(dpopProofValidator.validate(any())).thenReturn("jti-1");
        doThrow(new RuntimeException("replay")).when(dpopReplayGuard).checkAndRecord("jti-1");

        assertThatThrownBy(() -> resolver.resolve(new TokenResolutionRequest(OPAQUE_TOKEN, DPOP_PROOF, HTTP_METHOD, HTTP_URI)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("replay");
    }

}
