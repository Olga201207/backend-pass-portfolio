package ding.co.backendportfolio.chapter4._4_event_with_external.facade;

import ding.co.backendportfolio.chapter2.entity.Member;
import ding.co.backendportfolio.chapter2.repository.MemberRepository;
import ding.co.backendportfolio.chapter4._3_event_with_lock.entity.EventWithLock;
import ding.co.backendportfolio.chapter4._3_event_with_lock.entity.EventWithLockParticipant;
import ding.co.backendportfolio.chapter4._3_event_with_lock.repository.EventWithLockRepository;
import ding.co.backendportfolio.chapter4._4_event_with_external.external.ExternalEventApi;
import ding.co.backendportfolio.chapter4._4_event_with_external.external.KakaoTalkMessageApi;
import ding.co.backendportfolio.chapter4._4_event_with_external.external.model.ExternalEventResponse;
import ding.co.backendportfolio.chapter4._4_event_with_external.repository.EventWithLockParticipantRepository;
import ding.co.backendportfolio.global.error.exception.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventJoinWithExternalApiFacade {
    private static final String TEST_PHONE_NUMBER = "01012341234";

    private final EventWithLockRepository eventRepository;
    private final EventWithLockParticipantRepository participantRepository;
    private final MemberRepository memberRepository;
    private final ExternalEventApi externalEventApi;
    private final KakaoTalkMessageApi kakaoTalkMessageApi;

    // 이 코드는 문제 발생 소지가 있음
    // 외부 api 까지 호출해서 하나의 transaction에 묶어버리는 로직이기 때문
    // 영향
    // 1. 트랜잭션이 너무 크기 때문에 성능 저하 발생 가능
    //    => 왜냐하면, 이 transactional을 하는 순간부터 db connection pool을 소비하게 되는데,
    //    그 순간부터 3번 로직인 외부 api 호출했을 때 500ms, 6번 로직인 외부 api를 호출했을 때 500ms가 소요됨
    //    즉 1000ms 가 기본적으로 해당 db connection 에서 발생함. => 최악의 경우 매 요청마다 약 3초씩 connection을 점유하게 되는 경우가 발생할 수 있음
    //    => 즉, 해당 api에 많은 요청이 몰리게 된다면, db connection pool이 부족해져서 시스템이 응답하지 못하는 상황이 발생할 수도 있음
    // 2. 외부 api 장애시 전체 transaction이 롤백되어야 함
    //    => 예를 들어, 유저의 입장에서 현재 내가 시도했던 이 예약이라는 이벤트가 외부 api에서 에러가 발생했을 때, 해당 api를 호출한 transaction도 전부 rollback 되어야 함
    //    예를 들어, 6번 로직에서 에러가 발생했다면, 3번 로직도 롤백이 되어야 한다.
    //    => 즉, 이 transaction은 외부 api에 의존하는 시스템이다.
    //    그러므로, 외부 api 가 실패하게 된다면, 우리 서비스는 반드시 실패한다라는 결과가 오게 된다.
    // 3. 카카오톡 알림 전송 실패는 메인 로직인가?에 대한 고민을 해야 한다.
    //    => join event의 transactional 범위에 카카오톡 api까지 들어가 있는데, 카카오톡 메세지 발송 같은 경우에는 이 api 트랜잭션에 전부 들어가야만 하나?
    //    유저가 api를 호출하는 이 이벤트 참가 버튼을 눌렀을 때, 반드시 카카오톡 알림이 보내져야 하나? => 아님
    //    => api 응답속도가 더 빠르기를 원하지 카카오톡 알림이 더 빠르게 오기를 기대하지 않음
    //    => 즉, 카카오톡 api는 예약의 핵심 로직이 아니라 부가적인 기능임
    //    따라서 카카오톡 알림 전송 로직은 이 메서드 내에서 아예 제외를 하는 작업이 필요
    //
    // 외부 api들을 믿으면 안됨.
    // 1. 외부 네트워크를 타는 순간 요청에 대한 응답값이 오지 않거나,
    // 2. 응답값을 그쪽에서 반환은 했는데, 우리 쪽에서 패킷을 타임아웃 등으로 못 받을 수도 있음
    // 네트워크 상에서 실패하는 경우는 너무 다양한데, 이 원인으로 인해서 우리 db 까지 전부 다 롤백시켜 버린다는 것은
    // => 시스템의 의존을 외부 api 쪽으로 하게 되는 것을 의미함
    //
    // 따라서 이 외부 api가 정말 필수적인 로직이라면,
    // 1. 외부 api가 실패했다면, 그 실패한 이유를 가지고 어떻게든 데이터를 보정할 수 있는 방법
    // 2. 어떻게 하면 내가 외부 api의 실제 호출 여부를 성공해 올 수 있는가, 또는 얘가 실패했으면 나중에 재시도할 수 있는 로직을 어떻게 만들 수 있을까를 고민해야 한다.

    @Transactional
    public void joinEvent(Long eventId, Long memberId) {
        // 1. 이벤트와 회원 정보 조회
        EventWithLock event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException("이벤트를 찾을 수 없습니다."));
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다."));

        // 2. 이벤트 참가자 수 증가
        event.increaseParticipants();
        eventRepository.saveAndFlush(event);

        // 3. 외부 API 호출하여 이벤트 참가 처리
        ExternalEventResponse response = externalEventApi.registerParticipant(
                eventId, memberId, event.getName()
        );

        if (!response.isSuccess()) {
            throw new RuntimeException("외부 API 호출 실패: " + response.getErrorMessage());
        }

        // 5. 참가자 정보 저장
        EventWithLockParticipant participant = EventWithLockParticipant.builder()
                .event(event)
                .member(member)
                .build();
        participantRepository.save(participant);

        // 6. 카카오톡 알림 발송 (테스트용 전화번호 사용)
        kakaoTalkMessageApi.sendEventJoinMessage(TEST_PHONE_NUMBER, event.getName());
    }
} 
