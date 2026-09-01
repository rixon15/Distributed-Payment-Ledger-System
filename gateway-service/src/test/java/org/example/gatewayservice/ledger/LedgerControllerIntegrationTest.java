package org.example.gatewayservice.ledger;

import com.google.protobuf.Timestamp;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.example.gatewayservice.PhantomTokenFilter;
import org.example.gatewayservice.auth.PhantomTokenResolver;
import org.example.gatewayservice.auth.exception.TokenResolutionException;
import org.example.gatewayservice.grpc.ResolvedTokenClientInterceptor;
import org.example.grpc.common.Money;
import org.example.grpc.ledger.AccountStatus;
import org.example.grpc.ledger.Balance;
import org.example.grpc.ledger.GetBalanceRequest;
import org.example.grpc.ledger.LedgerServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class LedgerControllerIntegrationTest {

    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private Server server;
    private ManagedChannel channel;
    private AtomicReference<String> capturedAuthHeader;
    private PhantomTokenResolver phantomTokenResolver;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        capturedAuthHeader = new AtomicReference<>();

        ServerInterceptor captureInterceptor = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                capturedAuthHeader.set(headers.get(AUTHORIZATION_KEY));
                return next.startCall(call, headers);
            }
        };

        String serverName = "in-process-" + System.nanoTime();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(ServerInterceptors.intercept(new FakeLedgerServiceImpl(), captureInterceptor))
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();

        LedgerServiceGrpc.LedgerServiceBlockingStub stub = LedgerServiceGrpc.newBlockingStub(channel)
                .withInterceptors(new ResolvedTokenClientInterceptor());

        phantomTokenResolver = mock(PhantomTokenResolver.class);

        mockMvc = MockMvcBuilders.standaloneSetup(new LedgerController(stub))
                .addFilters(new PhantomTokenFilter(phantomTokenResolver))
                .build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void getBalance_propagatesResolvedJwt_toDownstreamGrpcCall() throws Exception {
        when(phantomTokenResolver.resolve(any())).thenReturn("resolved-jwt-value");

        mockMvc.perform(get("/balance")
                        .param("currency", "USD")
                        .header("Authorization", "Bearer opaque-token")
                        .header("DPoP", "dpop-proof-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-1"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.amount").value("100.00"));

        assertThat(capturedAuthHeader.get()).isEqualTo("Bearer resolved-jwt-value");
    }

    @Test
    void getBalance_rejectsRequest_whenAuthorizationHeaderMissing() throws Exception {
        mockMvc.perform(get("/balance")
                        .param("currency", "USD")
                        .header("DPoP", "dpop-proof-jwt"))
                .andExpect(status().isUnauthorized());

        assertThat(capturedAuthHeader.get()).isNull();
    }

    @Test
    void getBalance_rejectsRequest_whenTokenResolutionFails() throws Exception {
        when(phantomTokenResolver.resolve(any())).thenThrow(new TokenResolutionException("invalid"));

        mockMvc.perform(get("/balance")
                        .param("currency", "USD")
                        .header("Authorization", "Bearer opaque-token")
                        .header("DPoP", "dpop-proof-jwt"))
                .andExpect(status().isUnauthorized());

        assertThat(capturedAuthHeader.get()).isNull();
    }

    private static class FakeLedgerServiceImpl extends LedgerServiceGrpc.LedgerServiceImplBase {
        @Override
        public void getBalance(GetBalanceRequest request, StreamObserver<Balance> responseObserver) {
            responseObserver.onNext(Balance.newBuilder()
                    .setAccountId("acct-1")
                    .setBalance(Money.newBuilder().setCurrency(request.getCurrency()).setAmount("100.00").build())
                    .setStatus(AccountStatus.ACTIVE)
                    .setUpdatedAt(Timestamp.newBuilder().setSeconds(1_700_000_000).build())
                    .build());

            responseObserver.onCompleted();
        }
    }
}
