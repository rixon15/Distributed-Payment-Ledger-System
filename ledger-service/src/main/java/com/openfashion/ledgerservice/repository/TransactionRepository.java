package com.openfashion.ledgerservice.repository;

import com.openfashion.ledgerservice.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    boolean existsByReferenceId(UUID referenceId);

    Optional<Transaction> findByReferenceId(UUID referenceId);

    List<Transaction> findAllByReferenceIdIn(Set<UUID> refIds);
}
