package org.example.gatewayservice.auth;

import lombok.RequiredArgsConstructor;
import org.example.gatewayservice.auth.exception.DpopValidationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DpopReplayGuard {

    private static final String KEY_PREFIX = "dpop-jti";

    private final RedisTemplate<String, String> redisTemplate;

    public void checkAndRecord(String jti) {
        Boolean firstUse = redisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + jti, "1", DpopProofValidator.FRESHNESS_WINDOW);

        if(firstUse == null || !firstUse)
            throw new DpopValidationException("DPoP proof jti has already been used");
    }

}
