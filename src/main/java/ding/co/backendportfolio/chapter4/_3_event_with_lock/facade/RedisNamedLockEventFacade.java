package ding.co.backendportfolio.chapter4._3_event_with_lock.facade;

import ding.co.backendportfolio.chapter4._3_event_with_lock.service.EventWithLockService;
import ding.co.backendportfolio.chapter6_improved._3_remote_redis_real_example.DistributedLockProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisNamedLockEventFacade {

    private final EventWithLockService eventWithLockService;
    private final DistributedLockProvider distributedLockProvider;

    private static final long LOCK_TIMEOUT_MS = 5_000;
    private static final long RETRY_DELAY_MS = 50;
    private static final int MAX_RETRY = 100;

    public void joinEvent(Long eventId, Long memberId) throws InterruptedException {
        String lockKey = "event-lock:" + eventId;

        for (int retry = 0; retry < MAX_RETRY; retry++) {
            if (!distributedLockProvider.tryLock(lockKey, LOCK_TIMEOUT_MS)) {
                Thread.sleep(RETRY_DELAY_MS);
                continue;
            }
            try {
                eventWithLockService.joinEventWithNamedLock(eventId, memberId);
                log.info("이벤트 참가 성공 - eventId: {}, memberId: {}, 시도횟수: {}", eventId, memberId, retry + 1);
                return;
            } catch (IllegalStateException e) {
                throw e; // 정원 초과 → 즉시 실패
            } finally {
                distributedLockProvider.releaseLock(lockKey);
            }
        }
        throw new IllegalStateException("Redis 락 획득 실패 (최대 재시도 초과)");
    }
}
