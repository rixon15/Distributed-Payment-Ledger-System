package org.example.paymentservice.grpc;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.example.grpc.payment.PaymentServiceGrpc;
import org.example.grpc.payment.SubmitTransferRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentServiceGrpcImplTest {

    private Server server;
    private ManagedChannel channel;
    private PaymentServiceGrpc.PaymentServiceBlockingStub stub;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = "in-process-" + System.nanoTime();

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new PaymentServiceGrpcImpl())
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();

        stub = PaymentServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void submitTransfer_roundTripsTroughGeneratedStubs() {
        SubmitTransferRequest request = SubmitTransferRequest.newBuilder().build();

        assertThatThrownBy(() -> stub.submitTransfer(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus()
                        .getCode()).isEqualTo(Status.Code.UNIMPLEMENTED));
    }

}
