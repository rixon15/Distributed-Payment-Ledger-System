package com.openfashion.ledgerservice.integration.e2e;

import com.openfashion.ledgerservice.integration.base.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class BusinessRuleBoundaryIntegrationTest extends AbstractIntegrationTest {

    @Test
    void selfTransfer_sameDebitAndCredit_isRejectedAndNoMutation() {

    }

    @Test
    void zeroAmount_transfer_isRejectedAndNoMutation() {

    }

    @Test
    void negativeAmount_transfer_isRejectedAndNoMutation() {

    }

    @Test
    void senderClosed_transfer_isRejected() {

    }

    @Test
    void currencyMismatch_senderUsdReceiverEur_transferRejectedNoMutation() {

    }

}
