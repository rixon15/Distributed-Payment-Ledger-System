package com.openfashion.ledgerservice.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisTrimmer {

    private final RedisTemplate<String, String> balanceTemplate;
    private static final String BATCH_DONE_STREAM = "ledger:stream:batch:done";

    @Scheduled(fixedDelay = 60_000)
    public void trimBatchDoneStream() {
        balanceTemplate.opsForStream().trim(BATCH_DONE_STREAM, 20_000, true);
    }

}
