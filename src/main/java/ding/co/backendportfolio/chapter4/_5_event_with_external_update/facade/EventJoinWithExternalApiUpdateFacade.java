package ding.co.backendportfolio.chapter4._5_event_with_external_update.facade;

import ding.co.backendportfolio.chapter4._3_event_with_lock.entity.EventWithLockParticipant;
import ding.co.backendportfolio.chapter4._4_event_with_external.external.ExternalEventApi;
import ding.co.backendportfolio.chapter4._4_event_with_external.external.KakaoTalkMessageApi;
import ding.co.backendportfolio.chapter4._4_event_with_external.external.model.ExternalEventResponse;
import ding.co.backendportfolio.chapter4._4_event_with_external.service.EventExternalUpdateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventJoinWithExternalApiUpdateFacade {
    private static final String TEST_PHONE_NUMBER = "01012341234";

    private final EventExternalUpdateService eventJoinService;
    private final ExternalEventApi externalEventApi;
    private final KakaoTalkMessageApi kakaoTalkMessageApi;

    // 긍정적인 사용자 경험 측면에서 transaction 실패를 적절하게 처리하는 방법
    // 나 이 이벤트 참여할거야.
    // 나 인터파크 dingco 서비스를 통해서 돈 낼거야
    // transaction 수행 실패 시
    // => case 1. 아 중간에 transaction이 실패해서 롤백했어요. 나중에 다시 시도하게요
    // => case 2. 아 일단 성공했어. 근데, external_id는 나중에 가져올게.
    // case2가 좋은 방법이다. 만에 하나 나중에 externalId를 가져오는 것에 실패해도 그때 인터파크에 전화해서 관리자가 수동으로 external id를 수정하는게 100배는 좋음
    // user 입장에서 성공하는 경험만 제공해야 함
    public void joinEvent(Long eventId, Long memberId) {
        // 1. 기존 서비스로 이벤트 참가 처리
        EventWithLockParticipant participant = eventJoinService.joinEventWithTransaction(eventId, memberId);

        // 2. 외부 API 호출 -> 실패하는 과정 정말 많음
        // 실패하는 경우
        // 2-1. 1번이 성공하고 2번 작업이 실패하는 경우
        // -> 우리 쪽 db에는 eventParticipant가 증가된 채로 남아 있음(event 의 current_participants += 1). 하지만, 2로 external_id를 얻지 못해서 ep entity에 해당 필드값 적용 못함
        // 2-2. 1번 성고 & 2번 응답값 수신 실패하는 경우
        // -> 우리쪽 db에는 eventParticipant(external id 없음) 생김. (event 의 current_participants += 1)
        // -> 심지어 외부 api를 통해 내 돈은 나감
        // -> 근데 웹서비스 들어가면, eventparticipant(externalid 없음)
        ExternalEventResponse response = externalEventApi.registerParticipant(
                eventId, memberId, participant.getEvent().getName()
        );

        if (!response.isSuccess()) {
            throw new RuntimeException("외부 API 호출 실패: " + response.getErrorMessage());
        }

        // 3. 외부 API 응답으로 참가자 정보 업데이트
        // 1,2 성공 -> eventParticipant save, 인터파크 쪽에서도 데이터 저장 완료
        // 인터파크의 데이터를 우리 eventParticipant(external_id = ${externalId})
        // 이 단계에서 작업이 실패하게 된다면, external id값이 우리 서버에 저장이 안되어서 external id값을 저장한다는 요구사항을 만족하지 못함
        eventJoinService.updateExternalId(participant, response.getExternalId());

        // 4. 카카오톡 알림 발송 (테스트용 전화번호 사용)
        kakaoTalkMessageApi.sendEventJoinMessage(TEST_PHONE_NUMBER, participant.getEvent().getName());
    }
}   
