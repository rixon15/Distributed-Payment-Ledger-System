package com.openfashion.ledgerservice.repository.implementation;

import com.openfashion.ledgerservice.model.Posting;
import com.openfashion.ledgerservice.model.PostingDirection;
import com.openfashion.ledgerservice.model.Transaction;
import com.openfashion.ledgerservice.repository.TransactionBatchRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class TransactionBatchRepositoryImp implements TransactionBatchRepository {

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public int[] upsertTransactions(List<Transaction> transactions) {
        String sql = """
                INSERT INTO transactions (
                    id, reference_id, type, status, metadata, effective_date, version, created_at
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (reference_id, type) DO NOTHING
                """;

        return jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(@NonNull PreparedStatement ps, int i) throws SQLException {
                Transaction tx = transactions.get(i);
                ps.setObject(1, tx.getId() != null ? tx.getId() : UUID.randomUUID());
                ps.setObject(2, tx.getReferenceId());
                ps.setString(3, tx.getType().name());
                ps.setString(4, tx.getStatus().name());
                ps.setString(5, tx.getMetadata()); // Ensure this is valid JSON string
                ps.setObject(6, Timestamp.from(tx.getEffectiveDate()));
                ps.setLong(7, tx.getVersion());
                ps.setObject(8, Timestamp.from(tx.getCreatedAt()));
            }

            @Override
            public int getBatchSize() {
                return transactions.size();
            }
        });

    }

    @Override
    @Transactional
    public void updateAccountBalances(List<Posting> filteredPostings) {
        if (filteredPostings.isEmpty()) return;

        // 1. Group by Account ID and calculate the total net change
        // We treat DEBIT/CREDIT logic here. Adjust the signs based on your business rules.
        Map<UUID, BigDecimal> accountChanges = filteredPostings.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getAccount().getId(),
                        Collectors.reducing(
                                BigDecimal.ZERO,
                                p -> p.getDirection() == PostingDirection.CREDIT ? p.getAmount() : p.getAmount().negate(),
                                BigDecimal::add
                        )
                ));

        // 2. Prepare the batch update
        List<UUID> accountIds = new ArrayList<>(accountChanges.keySet());
        String sql = """
        UPDATE accounts
        SET balance = balance + ?, 
            version = version + 1, 
            updated_at = CURRENT_TIMESTAMP 
        WHERE id = ?
        """;

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(@NonNull PreparedStatement ps, int i) throws SQLException {
                UUID accountId = accountIds.get(i);
                BigDecimal netChange = accountChanges.get(accountId);

                ps.setBigDecimal(1, netChange);
                ps.setObject(2, accountId);
            }

            @Override
            public int getBatchSize() {
                return accountIds.size();
            }
        });
    }
}
