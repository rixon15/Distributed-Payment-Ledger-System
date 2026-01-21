package com.openfashion.ledgerservice.repository;

import com.openfashion.ledgerservice.model.Posting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PostingRepository extends JpaRepository<Posting, UUID> {
}
