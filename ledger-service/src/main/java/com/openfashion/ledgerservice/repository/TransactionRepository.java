package com.openfashion.ledgerservice.repository;

import com.openfashion.ledgerservice.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    boolean existsByReferenceId(String referenceId);

    Optional<Transaction> findByReferenceId(String referenceId);

}
