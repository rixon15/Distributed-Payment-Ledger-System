package com.openfashion.ledgerservice.repository;

import com.openfashion.ledgerservice.model.Account;
import com.openfashion.ledgerservice.model.CurrencyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByUserIdAndCurrency(UUID userId, CurrencyType currency);
    Optional<Account> findByNameAndCurrency(String name, CurrencyType currency);

}
