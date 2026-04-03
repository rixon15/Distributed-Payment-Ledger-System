package com.openfashion.ledgerservice.integration.transaction;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.integration.base.AbstractIntegrationTest;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.repository.OutboxRepository;
import com.openfashion.ledgerservice.service.LedgerBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class TransactionPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LedgerBatchService ledgerBatchService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private OutboxRepository outboxRepository;

    @BeforeEach
    void cleanDb() {
        jdbcTemplate.execute("TRUNCATE TABLE postings, outbox_events, transactions, accounts CASCADE");
    }

    @Test
    void duplicateReferenceAndType_isIgnoredByUpsert_andBalancesApplyOnce() {
        Account sender = createUserAccount(UUID.randomUUID(), "SENDER", "100.0000");
        Account receiver = createUserAccount(UUID.randomUUID(), "RECEIVER", "0.0000");

        UUID referenceId = UUID.randomUUID();
        TransactionRequest request = transfer(referenceId, sender.getId(), receiver.getId());

        ledgerBatchService.saveTransactions(List.of(request));
        ledgerBatchService.saveTransactions(List.of(request));

        Integer txCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE reference_id = ?",
                Integer.class,
                referenceId
        );
        Integer postingCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM postings p
                        JOIN transactions t ON t.id = p.transaction_id
                        WHERE t.reference_id = ? AND t.type = 'TRANSFER'
                        """,
                Integer.class,
                referenceId
        );
        Integer outboxCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM outbox_events
                        WHERE event_type = 'TRANSFER'
                        AND payload->>'referenceId' = ?
                        """,
                Integer.class,
                referenceId.toString()
        );

        assertThat(txCount).isEqualTo(1);
        assertThat(postingCount).isEqualTo(2);
        assertThat(outboxCount).isEqualTo(1);

        assertThat(readBalance(sender.getId())).isEqualByComparingTo("75.0000");
        assertThat(readBalance(receiver.getId())).isEqualByComparingTo("25.0000");
    }

    @Test
    void saveTransactions_whenOutboxSaveFails_rollsBackTransactionsPostingsAndBalances() {
        Account sender = createUserAccount(UUID.randomUUID(), "SENDER", "100.0000");
        Account receiver = createUserAccount(UUID.randomUUID(), "RECEIVER", "0.0000");

        UUID referenceId = UUID.randomUUID();
        TransactionRequest request = transfer(referenceId, sender.getId(), receiver.getId());

        doThrow(new RuntimeException("forced outbox failure"))
                .when(outboxRepository)
                .saveAll(any());

        List<TransactionRequest> requests = List.of(request);

        assertThatThrownBy(() -> ledgerBatchService.saveTransactions(requests))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("forced outbox failure");

        Integer txCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE reference_id = ?",
                Integer.class,
                referenceId
        );
        Integer postingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM postings", Integer.class);
        Integer outboxCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class);

        assertThat(txCount).isZero();
        assertThat(postingCount).isZero();
        assertThat(outboxCount).isZero();

        assertThat(readBalance(sender.getId())).isEqualByComparingTo("100.0000");
        assertThat(readBalance(receiver.getId())).isEqualByComparingTo("0.0000");
    }

    @Test
    void missingCreditAccount_skipsRequest_withoutPartialDebitOrArtifacts() {

        Account sender = createUserAccount(UUID.randomUUID(), "SENDER", "100.0000");
        UUID missingCreditAccountId = UUID.randomUUID();

        UUID referenceId = UUID.randomUUID();
        TransactionRequest request = transfer(referenceId, sender.getId(), missingCreditAccountId);

        ledgerBatchService.saveTransactions(List.of(request));

        Integer txCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE reference_id = ?",
                Integer.class,
                referenceId
        );
        Integer postingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM postings", Integer.class);
        Integer outboxCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class);


        assertThat(txCount).isZero();
        assertThat(postingCount).isZero();
        assertThat(outboxCount).isZero();

        assertThat(readBalance(sender.getId())).isEqualByComparingTo("100.0000");

    }

    private Account createUserAccount(UUID userId, String name, String balance) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName(name);
        account.setType(AccountType.ASSET);
        account.setCurrency(CurrencyType.USD);
        account.setBalance(new BigDecimal(balance));
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.saveAndFlush(account);
    }

    private TransactionRequest transfer(UUID referenceId, UUID debitAccountId, UUID creditAccountId) {
        TransactionRequest req = new TransactionRequest();
        req.setReferenceId(referenceId);
        req.setType(TransactionType.TRANSFER);
        req.setAmount(new BigDecimal("25.0000"));
        req.setCurrency(CurrencyType.USD);
        req.setDebitAccountId(debitAccountId);
        req.setCreditAccountId(creditAccountId);
        req.setSenderId(UUID.randomUUID());
        req.setReceiverId(UUID.randomUUID());
        return req;
    }

    private BigDecimal readBalance(UUID accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?",
                BigDecimal.class,
                accountId
        );
    }
}
