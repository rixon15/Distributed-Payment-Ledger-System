package org.example.authorizationservice.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.RequiredArgsConstructor;
import org.example.authorizationservice.core.crypto.SigningKeyEncryptionService;
import org.example.authorizationservice.model.SigningKeyEntity;
import org.example.authorizationservice.model.SigningKeyStatus;
import org.example.authorizationservice.repository.SigningKeyRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class SigningKeyLifecycleService {

    // Arbitrary but must stay stable across deploys — it's the coordination key every instance
    // uses to agree "I'm the one doing key lifecycle work right now."
    private static final long ADVISORY_LOCK_KEY = 727_001L;
    private static final int RSA_KEY_SIZE_BITS = 3072;

    private final SigningKeyRepository signingKeyRepository;
    private final SigningKeyEncryptionService signingKeyEncryptionService;
    private final JdbcTemplate jdbcTemplate;

    private final ObjectProvider<SigningKeyLifecycleService> selfProvider;
    @Value("${signing-key.rotation-period}")
    private Duration rotationPeriod;
    @Value("${signing-key.retirement-grace-period}")
    private Duration retirementGracePeriod;

    private final AtomicReference<SigningKeyCache> cache = new AtomicReference<>();

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        selfProvider.getObject().checkAndRotate();
    }

    @Scheduled(cron = "${signing-key.rotation-check-cron}")
    @Transactional
    public void checkAndRotate() {
        if (tryAcquireLock()) {
            ensureSigningKey();
            sweepRetiredKeys();
            reloadCache();
        } else {
            refreshCacheIfStale();
        }
    }

    // Force rotation outside of cadence for a test endpoint
    @Transactional
    public void rotateNow() {
        if (!tryAcquireLock())
            throw new IllegalStateException("Another instance is currently performing a signing key rotation.");

        rotate(signingKeyRepository.findByStatus(SigningKeyStatus.SIGNING).orElse(null));
        reloadCache();
    }

    public JWKSet jwkSet() {
        return requireCache().jwkSet();
    }

    private boolean tryAcquireLock() {
        Boolean acquired = jdbcTemplate.queryForObject(
                "select pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);

        return Boolean.TRUE.equals(acquired);
    }

    private void ensureSigningKey() {
        Optional<SigningKeyEntity> current = signingKeyRepository.findByStatus(SigningKeyStatus.SIGNING);

        if (current.isEmpty()) {
            createSigningKey();
            return;
        }

        SigningKeyEntity signingKey = current.get();
        if (signingKey.getCreatedAt().plus(rotationPeriod).isBefore(OffsetDateTime.now())) rotate(signingKey);
    }

    private void rotate(@Nullable SigningKeyEntity currentSigningKey) {
        if (currentSigningKey != null) {
            currentSigningKey.setStatus(SigningKeyStatus.VERIFY_ONLY);
            currentSigningKey.setRetireAt(OffsetDateTime.now().plus(retirementGracePeriod));
            // saveAndFlush, not save: Hibernate's default flush order runs all inserts before all
            // updates regardless of call order, so a plain save() here would let createSigningKey()'s
            // INSERT hit the DB before this UPDATE — briefly violating the "only one SIGNING row" constraint.
            signingKeyRepository.saveAndFlush(currentSigningKey);
        }

        createSigningKey();
    }

    private void createSigningKey() {
        KeyPair keyPair = generateRsaKeyPair();
        byte[] wrappedPrivateKey = signingKeyEncryptionService.encrypt(keyPair.getPrivate().getEncoded());

        SigningKeyEntity entity = new SigningKeyEntity();
        entity.setKeyId(UUID.randomUUID().toString());
        entity.setWrappedPrivateKey(wrappedPrivateKey);
        entity.setStatus(SigningKeyStatus.SIGNING);
        entity.setCreatedAt(OffsetDateTime.now());
        signingKeyRepository.save(entity);
    }

    private KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_SIZE_BITS);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to generate RSA key pair", e);
        }
    }

    private void sweepRetiredKeys() {
        List<SigningKeyEntity> expired = signingKeyRepository.findByStatusAndRetireAtBefore(
                SigningKeyStatus.VERIFY_ONLY, OffsetDateTime.now());

        expired.forEach(key -> key.setStatus(SigningKeyStatus.RETIRED));
        signingKeyRepository.saveAll(expired);
    }

    private void reloadCache() {
        List<SigningKeyEntity> activeKeys = signingKeyRepository.findByStatusIn(
                List.of(SigningKeyStatus.SIGNING, SigningKeyStatus.VERIFY_ONLY));

        String currentSigningKeyId = activeKeys.stream()
                .filter(key -> key.getStatus() == SigningKeyStatus.SIGNING)
                .map(SigningKeyEntity::getKeyId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No signing key available after lifecycle check"));

        List<JWK> jwks = activeKeys.stream()
                .map(key -> {
                    RSAKey rsaKey = decryptToRsaKey(key);
                    return key.getStatus() == SigningKeyStatus.SIGNING ? rsaKey : rsaKey.toPublicJWK();
                })
                .map(JWK.class::cast)
                .toList();

        cache.set(new SigningKeyCache(currentSigningKeyId, new JWKSet(jwks)));
    }

    private void refreshCacheIfStale() {
        SigningKeyCache cached = cache.get();

        Optional<SigningKeyEntity> current = signingKeyRepository.findByStatus(SigningKeyStatus.SIGNING);

        boolean stale = cached == null
                || current.isEmpty()
                || !current.get().getKeyId().equals(cached.currentSigningKeyId());

        if (stale) reloadCache();
    }

    private RSAKey decryptToRsaKey(SigningKeyEntity entity) {
        byte[] pkcs8Bytes = signingKeyEncryptionService.decrypt(entity.getWrappedPrivateKey());

        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Bytes));
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));

            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(entity.getKeyId())
                    .algorithm(JWSAlgorithm.PS256)
                    .keyUse(KeyUse.SIGNATURE)
                    .build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to reconstruct signing key " + entity.getKeyId(), e);
        } finally {
            Arrays.fill(pkcs8Bytes, (byte) 0);
        }
    }

    private SigningKeyCache requireCache() {
        SigningKeyCache cached = cache.get();
        if (cached == null) throw new IllegalStateException("signing key cache not yet initialized");

        return cached;
    }

    private record SigningKeyCache(String currentSigningKeyId, JWKSet jwkSet) {
    }
}

