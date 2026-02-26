package ding.co.backendportfolio.chapter4.fixture;

import ding.co.backendportfolio.chapter2.entity.Member;
import ding.co.backendportfolio.chapter4._3_event_with_lock.entity.EventWithLock;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ConcurrentTestUtil {

    @FunctionalInterface
    public interface EventJoinTask {
        void join(Long eventId, Long memberId) throws Exception;
    }

    public static void executeNonConflictingJoins(
            List<EventWithLock> events,  // 이벤트 리스트
            List<Member> members,        // 회원 리스트
            EventJoinTask joinTask       // 실행할 작업
    ) throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(events.size());

        for (int i = 0; i < events.size(); i++) {
            final int index = i;
            executorService.submit(() -> {
                try {
                    joinTask.join(events.get(index).getId(), members.get(index).getId());
                } catch (Exception e) {
                    log.error("이벤트 참가 실패: {}", e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();
    }

    // event id 1
    // members size = 100
    // 100개의 스레드를 만들어서
    public static void executeConcurrentJoins(
            Long eventId,
            List<Member> members,
            EventJoinTask joinTask
    ) throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(members.size());

        // 32개 thread pool 에서 100개의 요청을 동시(이론적으로 32개씩만 동시에 가능하겠지만)에 수행
        // 32개가 충돌이 많이 발생할 테니까 -> 낙관적 락보다 성능이 괜찮을 수 있음
        for (int i = 0; i < members.size(); i++) {
            // 각 스레드에서 i 번째 member 를 이벤트에 참여하도록 만든다.
            // eventJoinTask 의 인터페이스를 따라서 조인하게 만든다.
            final int index = i;
            executorService.submit(() -> {
                try {
                    joinTask.join(eventId, members.get(index).getId());
                } catch (Exception e) {
                    log.error("이벤트 참가 실패: {}", e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();
    }
} 
