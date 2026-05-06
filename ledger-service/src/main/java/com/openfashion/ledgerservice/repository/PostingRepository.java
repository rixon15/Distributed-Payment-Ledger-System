package com.openfashion.ledgerservice.repository;

import com.openfashion.ledgerservice.model.Posting;
import com.openfashion.ledgerservice.model.PostingDirection;
import com.openfashion.ledgerservice.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for immutable double-entry postings.
 *
 * <p>Each persisted transaction is expected to produce debit/credit postings
 * that capture the accounting movement at line-item level.
 */
@Repository
public interface PostingRepository extends JpaRepository<Posting, UUID> {
    List<Posting> findAllByTransactionReferenceId(UUID transactionReferenceId);
}
