package com.openfashion.ledgerservice.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.example.grpc.ledger.*;
import org.springframework.stereotype.Service;

@Service
public class LedgerServiceGrpcImpl extends LedgerServiceGrpc.LedgerServiceImplBase {

    @Override
    public void getBalance(GetBalanceRequest request, StreamObserver<Balance> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.withDescription("getBalance not yet implemented").asRuntimeException());
    }

    @Override
    public void listBalances(ListBalanceRequest request, StreamObserver<ListBalanceResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.withDescription("listBalances not yet implemented").asRuntimeException());
    }

    @Override
    public void getStatement(GetStatementRequest request, StreamObserver<GetStatementResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.withDescription("getStatement not yet implemented").asRuntimeException());
    }

    @Override
    public void getTransaction(GetTransactionRequest request, StreamObserver<TransactionDetail> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.withDescription("getTransaction not yet implemented").asRuntimeException());
    }
}
