package org.example.authorizationservice.grpc;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.example.grpc.auth.AuthServiceGrpc;
import org.example.grpc.auth.InvalidReason;
import org.example.grpc.auth.TokenExchangeRequest;
import org.example.grpc.auth.TokenExchangeResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AuthServiceGrpcImplTest {

    private Server server;
    private ManagedChannel channel;
    private AuthServiceGrpc.AuthServiceBlockingStub stub;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = "in-process-" + System.nanoTime();

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new AuthServiceGrpcImpl())
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
    void exchangeToken_roundTripsThroughGeneratedStubs() {
        TokenExchangeRequest request = TokenExchangeRequest.newBuilder()
                .setOpaqueToken("dummy-token")
                .setDpopProof("dummy-proof")
                .build();

        TokenExchangeResponse response = stub.exchangeToken(request);

        assertThat(response.getResultCase()).isEqualTo(TokenExchangeResponse.ResultCase.INVALID);
        assertThat(response.getInvalid().getReason()).isEqualTo(InvalidReason.MALFORMED);
    }

}
