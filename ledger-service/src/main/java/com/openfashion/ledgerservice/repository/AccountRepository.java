package com.openfashion.ledgerservice.repository;

import com.openfashion.ledgerservice.model.Account;
import com.openfashion.ledgerservice.model.CurrencyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Repository for ledger accounts used by strategy resolution and balance-related lookups.
 *
 * <p>This repository serves both user-owned accounts and named system accounts
 * such as liquidity or pending-withdrawal accounts.
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByUserIdAndCurrency(UUID userId, CurrencyType currency);
    Optional<Account> findByNameAndCurrency(String name, CurrencyType currency);

    List<Account> findByUserIdIn(Set<UUID> userIds);

}
