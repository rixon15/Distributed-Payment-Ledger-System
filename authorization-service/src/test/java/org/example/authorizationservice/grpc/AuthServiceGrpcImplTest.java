package org.example.authorizationservice.grpc;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.example.authorizationservice.core.exception.UnknownResourceException;
import org.example.authorizationservice.service.TokenExchangeService;
import org.example.grpc.auth.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceGrpcImplTest {

    private final TokenExchangeService tokenExchangeService = mock(TokenExchangeService.class);

    private Server server;
    private ManagedChannel channel;
    private AuthServiceGrpc.AuthServiceBlockingStub stub;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = "in-process-" + System.nanoTime();

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new AuthServiceGrpcImpl(tokenExchangeService))
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();

        stub = AuthServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void exchangeToken_passesTheRequestFieldsOnAndReturnsTheResult() {
        TokenExchangeResponse refused = TokenExchangeResponse.newBuilder()
                .setInvalid(InvalidToken.newBuilder().setReason(InvalidReason.REVOKED))
                .build();
        when(tokenExchangeService.exchange("opaque-token", "dpop-proof", "ledger-service")).thenReturn(refused);

        TokenExchangeResponse response = stub.exchangeToken(TokenExchangeRequest.newBuilder()
                .setOpaqueToken("opaque-token")
                .setDpopProof("dpop-proof")
                .setResource("ledger-service")
                .build());

        assertThat(response).isEqualTo(refused);
    }

    @Test
    void exchangeToken_unknownResourceFailsTheCallWithInvalidArgument() {
        when(tokenExchangeService.exchange("opaque-token", "dpop-proof", "billing-service"))
                .thenThrow(new UnknownResourceException("billing-service"));

        TokenExchangeRequest request = TokenExchangeRequest.newBuilder()
                .setOpaqueToken("opaque-token")
                .setDpopProof("dpop-proof")
                .setResource("billing-service")
                .build();

        assertThatThrownBy(() -> stub.exchangeToken(request))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    assertThat(e.getStatus().getDescription()).contains("billing-service");
                });
    }
}
