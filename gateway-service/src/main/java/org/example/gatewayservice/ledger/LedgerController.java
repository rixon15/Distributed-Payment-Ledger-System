package org.example.gatewayservice.ledger;

import lombok.RequiredArgsConstructor;
import org.example.grpc.common.CurrencyType;
import org.example.grpc.ledger.Balance;
import org.example.grpc.ledger.GetBalanceRequest;
import org.example.grpc.ledger.LedgerServiceGrpc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerServiceGrpc.LedgerServiceBlockingStub ledgerServiceStub;

    @GetMapping("/balance")
    public BalanceResponse getBalance(@RequestParam CurrencyType currency) {
        Balance balance = ledgerServiceStub.getBalance(GetBalanceRequest.newBuilder()
                .setCurrency(currency)
                .build());

        return toResponse(balance);
    }

    private BalanceResponse toResponse(Balance balance) {
        return new BalanceResponse(
                balance.getAccountId(),
                balance.getBalance().getCurrency().name(),
                balance.getBalance().getAmount(),
                balance.getStatus().name(),
                Instant.ofEpochSecond(balance.getUpdatedAt().getSeconds(), balance.getUpdatedAt().getNanos())
        );
    }
}
