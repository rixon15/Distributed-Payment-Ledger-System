package com.openfashion.ledgerservice.repository;

import com.openfashion.ledgerservice.model.Transaction;
import com.openfashion.ledgerservice.model.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Repository for persisted ledger transactions.
 *
 * <p>Transactions represent the immutable business-level journal entry that postings
 * and outbox result events are derived from.
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    List<Transaction> findAllByReferenceIdIn(Set<UUID> referenceIds);

    Optional<Transaction> findByReferenceIdAndType(UUID goodReef, TransactionType transactionType);
}
