package ding.co.backendportfolio.chapter4._5_event_with_external_update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import ding.co.backendportfolio.chapter2.entity.Member;
import ding.co.backendportfolio.chapter4._3_event_with_lock.entity.EventWithLock;
import ding.co.backendportfolio.chapter4._4_event_with_external.external.ExternalEventApi;
import ding.co.backendportfolio.chapter4._4_event_with_external.external.model.ExternalEventResponse;
import ding.co.backendportfolio.chapter4._4_event_with_external.service.EventExternalUpdateService;
import ding.co.backendportfolio.chapter4._5_event_with_external_update.facade.EventJoinWithExternalApiUpdateFacade;
import ding.co.backendportfolio.chapter4.fixture.Chapter4Fixture;
import ding.co.backendportfolio.chapter4.fixture.ConcurrentTestUtil;
import ding.co.backendportfolio.config.IntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

@Slf4j
@IntegrationTest
class EventJoinWithExternalApiUpdateFacadeTest {
    @Autowired
    private EventJoinWithExternalApiUpdateFacade externalApiResponseFacade;

    // MockBean, SpyBean -> 둘다 테스트용으로 한번 감싸서 만들것들
    // MockBean -> 설정 초기에는 null. 행동을 정의해주지 않으면 그냥 에러가 발생
    // 특정 상황에 대해서 완전 결과를 제어하고 싶을 수가 있음
    // 3번 과정(updateExternalId 라는 것을 호출했을 때, 실패하길 원함)
    // 이번의 경우에는 EventExternalUpdateService.updateExternalId 의 동작 결과가 실패하도록 설정하기 위해 mockbean으로 설정한 것
    // mocking -> 테스트 시나리오 설계를 위해 특정 작업 수행 결과를 의도적으로 제어하는 방식
    // MockBean -> mocking 방식 중 하나
    // => repository 이벤트 자체를 의도적으로 실패하도록 만드는 일은 매우 번거로운 일 => 그래서 아예 이 서비스의 특정 메서드를 어떤 방식으로 수행해라라는 식으로 정의하는 방식을 모킹이라 한다.
    // => 이 mocking 방식을 이용해서 해당 로직을 우리가 테스트해보고자 했던 3번 로직(updateExternalId)를 실패하도록 만들겠다. => 그래서 mockBean을 사용하도록 만들었다.
    @MockBean
    private EventExternalUpdateService eventJoinService;

    // SpyBean -> mocking 보다는 조금 더 가벼운 행위
    // Spy -> 감시하다. 해당 빈이 어떤식으로 동작하는지 감시하기 위한 wrapping 객체
    // 이번의 경우에는 이 SpyBean으로 만들어 놓은 externalEventApi 객체가 호출되는 시점에 어떤 행위를 해라 라는 식으로 감시하도록 만들 수 있음
    // => 우리는 externalEventApi를 감시하기 위해서 SpyBean으로 정의했다
    @SpyBean
    private ExternalEventApi externalEventApi;

    private EventWithLock testEvent;
    private List<Member> testMembers;
    private AtomicInteger externalApiCallCount;
    private AtomicInteger updateExternalIdCallCount;
    private AtomicInteger updateExternalIdSuccessCount;

    @BeforeEach
    void setUp() {
        testEvent = Chapter4Fixture.createEventWithLock("테스트 이벤트", 20);
        testMembers = IntStream.range(0, 20)
                .mapToObj(i -> Chapter4Fixture.createTestMember("테스트유저" + i))
                .collect(Collectors.toList());

        // 외부 api 호출(EventJoinWithExternalApiUpdateFacade 중 외부 api)을 얼마나 성공했는가를 저장하기 위한 변수
        externalApiCallCount = new AtomicInteger(0);
        // 3번 과정을 얼마나 호출했는지
        updateExternalIdCallCount = new AtomicInteger(0);
        // 3번 과정을 얼마나 성공했는지
        updateExternalIdSuccessCount = new AtomicInteger(0);

        // mockBean(EventExternalUpdateService) 동작 방식 정의
        // 특정 메서드 호출시 특정 값 반환하도록 설정. 항상 성공하도록 설정
        doAnswer(invocation -> {
            Long eventId = invocation.getArgument(0);
            Long memberId = invocation.getArgument(1);
            Member testMember = Chapter4Fixture.createTestMember("테스트유저" + memberId);
            return Chapter4Fixture.createTestParticipant(testEvent, testMember);
        }).when(eventJoinService).joinEventWithTransaction(any(), any());

        // 50퍼센트 확률로 실패하도록 설정
        doAnswer(invocation -> {
            updateExternalIdCallCount.incrementAndGet();
            if (Math.random() >= 0.5) {  // 50% 확률로 실패
                updateExternalIdSuccessCount.incrementAndGet();
                return null;
            }
            throw new RuntimeException("DB 업데이트 실패 시뮬레이션");
        }).when(eventJoinService).updateExternalId(any(), any());

        // externalApiCallCount에 대해 externalEventApi가 registerParticipant라는 메서드를 호출했을 때, 올바른 값을 반환하고 externalApiCallCount를 증가하도록 만들었음
        doAnswer(invocation -> {
            externalApiCallCount.incrementAndGet();
            return ExternalEventResponse.builder()
                    .success(true)
                    .externalId(UUID.randomUUID().toString())
                    .errorMessage(null)
                    .build();
        }).when(externalEventApi).registerParticipant(any(), any(), any());
    }

    @Test
    @DisplayName("외부 API 호출과 DB 업데이트 불일치 테스트")
    void externalApiCallAndDbUpdateMismatchTest() throws InterruptedException {
        // when
        ConcurrentTestUtil.executeConcurrentJoins(
            testEvent.getId(),
            testMembers,
            (eventId, memberId) -> externalApiResponseFacade.joinEvent(eventId, memberId)
        );

        // then
        logTestResults();

        assertThat(externalApiCallCount.get())
                .as("외부 API 호출 횟수가 DB 업데이트 성공 횟수보다 많아야 함")
                .isGreaterThan(updateExternalIdSuccessCount.get());

        double actualSuccessRate = (double) updateExternalIdSuccessCount.get() / updateExternalIdCallCount.get();
        assertThat(actualSuccessRate)
                .as("DB 업데이트 성공률이 약 50%여야 함")
                .isBetween(0.3, 0.7);  // 30%~70% 범위 내 성공률 허용
    }

    private void logTestResults() {
        log.info("=== 외부 API 호출과 DB 업데이트 불일치 테스트 결과 ===");
        log.info("총 요청 수: {}", 20);
        log.info("외부 API 호출 횟수: {}", externalApiCallCount.get());
        log.info("DB 업데이트 시도 횟수: {}", updateExternalIdCallCount.get());
        log.info("DB 업데이트 성공 횟수: {}", updateExternalIdSuccessCount.get());
        log.info("불일치 건수: {}", (externalApiCallCount.get() - updateExternalIdSuccessCount.get()));
        log.info("DB 업데이트 성공률: {:.2f}%", 
                (double) updateExternalIdSuccessCount.get() / updateExternalIdCallCount.get() * 100);
    }
} 
