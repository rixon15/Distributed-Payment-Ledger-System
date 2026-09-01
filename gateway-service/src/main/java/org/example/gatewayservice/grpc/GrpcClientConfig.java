package org.example.gatewayservice.grpc;

import org.example.grpc.auth.AuthServiceGrpc;
import org.example.grpc.ledger.LedgerServiceGrpc;
import org.example.grpc.payment.PaymentServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class GrpcClientConfig {

    @Bean
    AuthServiceGrpc.AuthServiceBlockingStub authServiceBlockingStub(GrpcChannelFactory channels) {
        return AuthServiceGrpc.newBlockingStub(channels.createChannel("auth-service"));
    }

    @Bean
    LedgerServiceGrpc.LedgerServiceBlockingStub ledgerServiceBlockingStub(
            GrpcChannelFactory channels, ResolvedTokenClientInterceptor interceptor) {

        return LedgerServiceGrpc.newBlockingStub(channels.createChannel("ledger-service"))
                .withInterceptors(interceptor);
    }

    @Bean
    PaymentServiceGrpc.PaymentServiceBlockingStub paymentServiceBlockingStub(
            GrpcChannelFactory channels, ResolvedTokenClientInterceptor interceptor) {
        return PaymentServiceGrpc.newBlockingStub(channels.createChannel("payment-service"))
                .withInterceptors(interceptor);
    }

}
