package org.example.authorizationservice.core.crypto;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class SigningKeyEncryptionService {

    private static final String WRAPPING_KEY_ALIAS = "alias/authorization-service-signing-key-encryption-key";

    private final KmsClient kmsClient;
    private final AtomicReference<String> wrappingKeyId = new AtomicReference<>();

    public byte[] encrypt(byte[] plaintextPrivateKey) {
        return kmsClient.encrypt(EncryptRequest.builder()
                        .keyId(resolveWrappingKeyId())
                        .plaintext(SdkBytes.fromByteArray(plaintextPrivateKey))
                        .build())
                .ciphertextBlob()
                .asByteArray();
    }

    public byte[] decrypt(byte[] ciphertext) {
        return kmsClient.decrypt(DecryptRequest.builder()
                        .keyId(resolveWrappingKeyId())
                        .ciphertextBlob(SdkBytes.fromByteArray(ciphertext))
                        .build())
                .plaintext()
                .asByteArray();
    }

    private String resolveWrappingKeyId() {
        String cached = wrappingKeyId.get();
        if (cached != null) return cached;

        String resolved = findOrCreateWrappingKey();
        wrappingKeyId.set(resolved);
        return resolved;
    }

    private String findOrCreateWrappingKey() {
        try {
            return kmsClient.describeKey(DescribeKeyRequest.builder().keyId(WRAPPING_KEY_ALIAS).build())
                    .keyMetadata()
                    .keyId();
        } catch (NotFoundException e) {
            return createWrappingKey();
        }
    }

    private String createWrappingKey() {
        String newKeyId = kmsClient.createKey(CreateKeyRequest.builder()
                        .keySpec(KeySpec.SYMMETRIC_DEFAULT)
                        .keyUsage(KeyUsageType.ENCRYPT_DECRYPT)
                        .build())
                .keyMetadata()
                .keyId();

        try {
            kmsClient.enableKeyRotation(EnableKeyRotationRequest.builder().keyId(newKeyId).build());
        } catch (RuntimeException _) {
            // best-effort — annual rotation of the wrapping key is hygiene, not correctness
        }

        try {
            kmsClient.createAlias(CreateAliasRequest.builder()
                    .aliasName(WRAPPING_KEY_ALIAS)
                    .targetKeyId(newKeyId)
                    .build());

            return newKeyId;
        } catch (AlreadyExistsException _) {
            return kmsClient.describeKey(DescribeKeyRequest.builder().keyId(WRAPPING_KEY_ALIAS).build())
                    .keyMetadata()
                    .keyId();
        }
    }
}
