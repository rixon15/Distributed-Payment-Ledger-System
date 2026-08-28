package org.example.authorizationservice.grpc;

import io.grpc.stub.StreamObserver;
import org.example.grpc.auth.*;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceGrpcImpl extends AuthServiceGrpc.AuthServiceImplBase {

    @Override
    public void exchangeToken(TokenExchangeRequest request, StreamObserver<TokenExchangeResponse> responseObserver) {
        TokenExchangeResponse response = TokenExchangeResponse.newBuilder()
                .setInvalid(InvalidToken.newBuilder()
                        .setReason(InvalidReason.MALFORMED)
                        .build())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
