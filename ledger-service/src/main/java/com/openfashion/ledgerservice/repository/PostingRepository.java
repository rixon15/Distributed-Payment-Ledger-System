package com.openfashion.ledgerservice.repository;

import com.openfashion.ledgerservice.model.Posting;
import com.openfashion.ledgerservice.model.PostingDirection;
import com.openfashion.ledgerservice.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PostingRepository extends JpaRepository<Posting, UUID> {
    Optional<Posting> findByTransactionAndDirection(Transaction transaction, PostingDirection postingDirection);
}
