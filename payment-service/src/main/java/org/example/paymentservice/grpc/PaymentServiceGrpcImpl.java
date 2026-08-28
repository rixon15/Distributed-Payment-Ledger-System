package org.example.paymentservice.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.example.grpc.payment.*;
import org.springframework.stereotype.Service;

@Service
public class PaymentServiceGrpcImpl extends PaymentServiceGrpc.PaymentServiceImplBase {

    @Override
    public void submitTransfer(SubmitTransferRequest request, StreamObserver<SubmitTransferResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.withDescription("submitTransfer not yet implemented").asRuntimeException());
    }

    @Override
    public void getTransferStatus(GetTransferStatusRequest request, StreamObserver<GetTransferStatusResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.withDescription("getTransferStatus not yet implemented").asRuntimeException());
    }
}
