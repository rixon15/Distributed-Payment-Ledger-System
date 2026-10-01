package org.example.authorizationservice.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import org.example.authorizationservice.core.exception.UnknownResourceException;
import org.example.authorizationservice.service.TokenExchangeService;
import org.example.grpc.auth.AuthServiceGrpc;
import org.example.grpc.auth.TokenExchangeRequest;
import org.example.grpc.auth.TokenExchangeResponse;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceGrpcImpl extends AuthServiceGrpc.AuthServiceImplBase {

    private final TokenExchangeService tokenExchangeService;

    @Override
    public void exchangeToken(TokenExchangeRequest request, StreamObserver<TokenExchangeResponse> responseObserver) {
        TokenExchangeResponse response;

        try {
            response = tokenExchangeService.exchange(
                    request.getOpaqueToken(), request.getDpopProof(), request.getResource());
        } catch (UnknownResourceException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        }

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
