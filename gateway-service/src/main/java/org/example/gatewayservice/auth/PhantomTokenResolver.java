package org.example.gatewayservice.auth;

import lombok.RequiredArgsConstructor;
import org.example.gatewayservice.auth.exception.TokenResolutionException;
import org.example.grpc.auth.AuthServiceGrpc;
import org.example.grpc.auth.ResolvedToken;
import org.example.grpc.auth.TokenExchangeRequest;
import org.example.grpc.auth.TokenExchangeResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PhantomTokenResolver {

    private final TokenCacheService tokenCacheService;
    private final DpopProofValidator dpopProofValidator;
    private final DpopReplayGuard dpopReplayGuard;
    private final AuthServiceGrpc.AuthServiceBlockingStub authServiceBlockingStub;

    public String resolve(TokenResolutionRequest request) {
        CachedToken token = tokenCacheService.get(request.opaqueToken())
                .orElseGet(() -> exchangeAndCache(request.opaqueToken(), request.dpopProof()));

        String jti = dpopProofValidator.validate(new DpopValidationRequest(
                request.dpopProof(), token.cnfJkt(), request.httpMethod(), request.httpUri(), request.opaqueToken()
        ));

        dpopReplayGuard.checkAndRecord(jti);

        return token.accessToken();
    }

    private CachedToken exchangeAndCache(String opaqueToken, String dpopProof) {
        TokenExchangeResponse response = authServiceBlockingStub.exchangeToken(TokenExchangeRequest.newBuilder()
                .setOpaqueToken(opaqueToken)
                .setDpopProof(dpopProof)
                .build());

        if(response.getResultCase() != TokenExchangeResponse.ResultCase.RESOLVED)
            throw new TokenResolutionException("Token exchange failed: " + response.getInvalid().getReason());

        ResolvedToken resolved = response.getResolved();
        CachedToken token = new CachedToken(
                resolved.getAccessToken(),
                resolved.getCnfJkt(),
                Instant.ofEpochSecond(resolved.getExpiresAt().getSeconds(), resolved.getExpiresAt().getNanos())
        );

        tokenCacheService.put(opaqueToken, token);

        return token;
    }
}
