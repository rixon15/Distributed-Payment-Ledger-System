package org.example.gatewayservice.ledger;

import com.google.protobuf.Timestamp;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.example.gatewayservice.PhantomTokenFilter;
import org.example.gatewayservice.auth.PhantomTokenResolver;
import org.example.gatewayservice.auth.TokenResolutionRequest;
import org.example.gatewayservice.auth.exception.DpopValidationException;
import org.example.gatewayservice.auth.exception.TokenResolutionException;
import org.example.gatewayservice.core.config.GatewayProperties;
import org.example.gatewayservice.core.error.GatewayExceptionHandler;
import org.example.gatewayservice.grpc.ResolvedTokenClientInterceptor;
import org.example.grpc.common.Money;
import org.example.grpc.ledger.AccountStatus;
import org.example.grpc.ledger.Balance;
import org.example.grpc.ledger.GetBalanceRequest;
import org.example.grpc.ledger.LedgerServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LedgerControllerIntegrationTest {

    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final String PUBLIC_BASE_URL = "https://api.example.test";

    private Server server;
    private ManagedChannel channel;
    private AtomicReference<String> capturedAuthHeader;
    private AtomicReference<Status> ledgerFailure;
    private PhantomTokenResolver phantomTokenResolver;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        capturedAuthHeader = new AtomicReference<>();
        ledgerFailure = new AtomicReference<>();

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
                .addService(ServerInterceptors.intercept(new FakeLedgerServiceImpl(ledgerFailure), captureInterceptor))
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();

        LedgerServiceGrpc.LedgerServiceBlockingStub stub = LedgerServiceGrpc.newBlockingStub(channel)
                .withInterceptors(new ResolvedTokenClientInterceptor());

        phantomTokenResolver = mock(PhantomTokenResolver.class);

        JsonMapper jsonMapper = JsonMapper.builder()
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                .build();

        mockMvc = MockMvcBuilders.standaloneSetup(new LedgerController(stub))
                .setControllerAdvice(new GatewayExceptionHandler())
                .addFilters(new PhantomTokenFilter(phantomTokenResolver, new GatewayProperties(PUBLIC_BASE_URL), jsonMapper))
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

        performAuthenticatedGetBalance()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-1"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.amount").value("100.00"));

        assertThat(capturedAuthHeader.get()).isEqualTo("Bearer resolved-jwt-value");
    }

    @Test
    void getBalance_passesFullPublicUriWithoutQuery_asDpopHtu() throws Exception {
        when(phantomTokenResolver.resolve(any())).thenReturn("resolved-jwt-value");
        ArgumentCaptor<TokenResolutionRequest> captor = ArgumentCaptor.forClass(TokenResolutionRequest.class);

        performAuthenticatedGetBalance().andExpect(status().isOk());

        verify(phantomTokenResolver).resolve(captor.capture());
        assertThat(captor.getValue().httpUri()).isEqualTo(PUBLIC_BASE_URL + "/balance");
        assertThat(captor.getValue().httpMethod()).isEqualTo("GET");
    }

    @Test
    void getBalance_rejectsRequest_whenAuthorizationHeaderMissing() throws Exception {
        mockMvc.perform(get("/balance")
                        .param("currency", "USD")
                        .header("DPoP", "dpop-proof-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "DPoP"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(capturedAuthHeader.get()).isNull();
    }

    @Test
    void getBalance_rejectsRequest_whenTokenResolutionFails() throws Exception {
        when(phantomTokenResolver.resolve(any())).thenThrow(new TokenResolutionException("invalid"));

        performAuthenticatedGetBalance()
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "DPoP error=\"invalid_token\""))
                .andExpect(jsonPath("$.detail").value("Invalid access token"));

        assertThat(capturedAuthHeader.get()).isNull();
    }

    @Test
    void getBalance_rejectsRequest_whenDpopProofInvalid_withoutLeakingReason() throws Exception {
        when(phantomTokenResolver.resolve(any())).thenThrow(new DpopValidationException("DPoP proof htu does not match request URI"));

        performAuthenticatedGetBalance()
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "DPoP error=\"invalid_dpop_proof\""))
                .andExpect(jsonPath("$.detail").value("Invalid DPoP proof"));
    }

    @Test
    void getBalance_returns503_whenAuthServiceUnavailable() throws Exception {
        when(phantomTokenResolver.resolve(any())).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        performAuthenticatedGetBalance()
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("WWW-Authenticate"));

        assertThat(capturedAuthHeader.get()).isNull();
    }

    @Test
    void getBalance_returns502_notUnauthorized_whenAuthServiceRejectsGateway() throws Exception {
        when(phantomTokenResolver.resolve(any())).thenThrow(new StatusRuntimeException(Status.UNAUTHENTICATED));

        performAuthenticatedGetBalance().andExpect(status().isBadGateway());
    }

    @Test
    void getBalance_mapsDownstreamNotFound_to404Problem() throws Exception {
        when(phantomTokenResolver.resolve(any())).thenReturn("resolved-jwt-value");
        ledgerFailure.set(Status.NOT_FOUND.withDescription("account 42 missing for user 7"));

        performAuthenticatedGetBalance()
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    void getBalance_forwardsDescription_forValidationFailures() throws Exception {
        when(phantomTokenResolver.resolve(any())).thenReturn("resolved-jwt-value");
        ledgerFailure.set(Status.INVALID_ARGUMENT.withDescription("currency must be specified"));

        performAuthenticatedGetBalance()
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("currency must be specified"));
    }

    @Test
    void getBalance_hidesDescription_forInternalFailures() throws Exception {
        when(phantomTokenResolver.resolve(any())).thenReturn("resolved-jwt-value");
        ledgerFailure.set(Status.INTERNAL.withDescription("could not acquire JDBC connection"));

        performAuthenticatedGetBalance()
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    private ResultActions performAuthenticatedGetBalance() throws Exception {
        return mockMvc.perform(get("/balance")
                .param("currency", "USD")
                .header("Authorization", "Bearer opaque-token")
                .header("DPoP", "dpop-proof-jwt"));
    }

    private static class FakeLedgerServiceImpl extends LedgerServiceGrpc.LedgerServiceImplBase {

        private final AtomicReference<Status> failure;

        FakeLedgerServiceImpl(AtomicReference<Status> failure) {
            this.failure = failure;
        }

        @Override
        public void getBalance(GetBalanceRequest request, StreamObserver<Balance> responseObserver) {

            if(failure.get() != null) {
                responseObserver.onError(failure.get().asRuntimeException());
                return;
            }

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
