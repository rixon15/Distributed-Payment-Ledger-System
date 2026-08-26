package org.example.authorizationservice.repository;

import org.example.authorizationservice.model.SigningKeyEntity;
import org.example.authorizationservice.model.SigningKeyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SigningKeyRepository extends JpaRepository<SigningKeyEntity, String> {

    Optional<SigningKeyEntity> findByStatus(SigningKeyStatus status);

    List<SigningKeyEntity> findByStatusIn(Collection<SigningKeyStatus> statuses);

    List<SigningKeyEntity> findByStatusAndRetireAtBefore(SigningKeyStatus status, OffsetDateTime cutoff);

}
