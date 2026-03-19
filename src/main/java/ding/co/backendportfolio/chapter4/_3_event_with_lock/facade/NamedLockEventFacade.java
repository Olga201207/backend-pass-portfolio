package ding.co.backendportfolio.chapter4._3_event_with_lock.facade;

import ding.co.backendportfolio.chapter4._3_event_with_lock.repository.EventWithLockRepository;
import ding.co.backendportfolio.chapter4._3_event_with_lock.service.EventWithLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class NamedLockEventFacade {
    private final EventWithLockService eventWithLockService;
    private final EventWithLockRepository eventWithLockRepository;

    private static final long RETRY_DELAY_MS = 50;

    // 1-A transaction (가칭)
    // 낙관적 락과 비슷한 방식으로 재시도가 들어가야 하는 로직임
    // 즉, version 등의 인수가 db의 record의 것과 다른 경우 재시도하는 로직이 필요하다는 의미
    // 네임드 락 자체도, 어떤 transaction이 락을 선점하면, 다른 transaction은 그 락을 잡기 위해서 대기해야 한다는 의미
    @Transactional
    public void joinEvent(Long eventId, Long memberId) throws InterruptedException {
        int retryCount = 0;
        String lockName = String.format("event_%d", eventId);

        while (true) {
            try {
                int lockResult = eventWithLockRepository.getLock(lockName, 3);
                // 락 획득 실패시
                if (lockResult <= 0) {
                    log.warn("락 획득 실패 - eventId: {}, memberId: {}", eventId, memberId);
                    // 50ms 동안 해당 thread를 잠재움, 이후 재시도
                    Thread.sleep(RETRY_DELAY_MS);
                    continue;
                }

                eventWithLockService.joinEventWithNamedLock(eventId, memberId);
                log.info("이벤트 참가 성공 - eventId: {}, memberId: {}, 총 시도횟수: {}",
                        eventId, memberId, retryCount + 1);
                return;
            } catch (IllegalStateException e) {
                // 정원 초과는 재시도해도 의미 없음 → 즉시 실패
                throw e;
            } catch (Exception e) {
                // 수행 중 에러 발생시, 재시도 횟수 증가시키고, 해당 스레드를 잠재운 뒤 재실행
                retryCount++;
                log.warn("이벤트 참가 재시도 - eventId: {}, memberId: {}, 현재 시도횟수: {}, error: {}",
                        eventId, memberId, retryCount, e.getMessage());
                Thread.sleep(RETRY_DELAY_MS);
            } finally {
                int releaseResult = eventWithLockRepository.releaseLock(lockName);
                if (releaseResult <= 0) {
                    log.error("락 해제 실패 - eventId: {}, memberId: {}", eventId, memberId);
                }
            }
        }
    }

//    // @Transactional 없음
//    public void joinEvent(Long eventId, Long memberId) {
//        String lockName = "event_" + eventId;
//        namedLockRepository.executeWithLock(lockName, () ->
//                eventWithLockService.joinEventWithNamedLock(eventId, memberId)
//        );
//    }
}
