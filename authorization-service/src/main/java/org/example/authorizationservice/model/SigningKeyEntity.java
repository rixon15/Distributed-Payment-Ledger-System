package org.example.authorizationservice.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "signing_keys")
public class SigningKeyEntity {

    @Id
    @Column(name = "key_id", length = 255)
    private String keyId;

    @Column(name = "wrapped_private_key", nullable = false)
    private byte[] wrappedPrivateKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SigningKeyStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "retire_at")
    private OffsetDateTime retireAt;

}
