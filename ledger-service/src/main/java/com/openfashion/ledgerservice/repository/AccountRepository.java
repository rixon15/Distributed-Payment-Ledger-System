package com.openfashion.ledgerservice.repository;

import com.openfashion.ledgerservice.model.Account;
import com.openfashion.ledgerservice.model.CurrencyType;
import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByUserIdAndCurrency(UUID userId, CurrencyType currency);
    Optional<Account> findByNameAndCurrency(String name, CurrencyType currency);

    @Modifying
    @Query("UPDATE Account a SET a.balance = a.balance + :change WHERE a.id = :id")
    void updateBalance(@Param("id") UUID id, @Param("change") BigDecimal change);
}
