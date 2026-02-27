package ding.co.backendportfolio.chapter4._5_event_with_external_update.service;

import ding.co.backendportfolio.chapter4._3_event_with_lock.entity.EventWithLockParticipant;
import ding.co.backendportfolio.chapter4._4_event_with_external.external.ExternalEventApi;
import ding.co.backendportfolio.chapter4._4_event_with_external.external.model.ExternalEventResponse;
import ding.co.backendportfolio.chapter4._4_event_with_external.repository.EventWithLockParticipantRepository;
import ding.co.backendportfolio.chapter4._4_event_with_external.service.EventExternalUpdateService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ExternalApiUpdateRecoveryService {
    private final EventWithLockParticipantRepository participantRepository;
    private final ExternalEventApi externalEventApi;
    private final EventExternalUpdateService eventJoinService;

    /**
     * 5분마다 실행되어 외부 ID가 없는 참가자들을 동기화
     */
    // 이렇게 뒤에서 후보정해도 괜찮나?
    // 현업에서는 "실시간성"보다 "일관성"이 훨씬 중요함.
    @Scheduled(fixedDelay = 300000) // 5분
    public void recoverMissingExternalIds() {
        // 1. external_id가 없는 참가자들 조회
        // external_id 가 없다면, db에 외부 api의 응답값이 반영(동기화) 되지 않았다를 의미
        // => 이것은 암시적인 부분. 이 코드를 처음보는 사람은 명시적으로 위 내용을 알 수가 없음
        // => external_id 가 null인 record들을 왜 조회하는지 모를 가능성이 크다는 말임
        // 명시적으로 표현하는 방법
        // => status = [PENDING, EXTERNAL_API_UPDATE_FAILED, COMPLETED, ...]
        // => 즉, 칼럼(external_id)값 존재 여부가 아닌 status를 이용해서 만약에 업데이트에 성공했으면, COMPLETED 라는 식으로 올바른 동기화 문제를 해결할 수도 있음
        List<EventWithLockParticipant> participantsWithoutExternalId = participantRepository.findByExternalIdIsNullAndCreatedAtBefore(
                LocalDateTime.now()
        );

        log.info("외부 ID 미할당 참가자 발견: {}건", participantsWithoutExternalId.size());

        // 2. 각 참가자별로 외부 시스템 조회 및 업데이트
        for (EventWithLockParticipant participant : participantsWithoutExternalId) {
            syncExternalId(participant);
        }
    }

    /**
     * 개별 참가자의 외부 ID 동기화
     */
    public void syncExternalId(EventWithLockParticipant participant) {
        // 1. 외부 시스템에서 참가자 정보 조회 (트랜잭션 밖에서)
        ExternalEventResponse response = externalEventApi.getParticipantInfo(
                participant.getEvent().getId(),
                participant.getMember().getId()
        );

        if (!response.isSuccess() || response.getExternalId() == null) {
            log.warn("참가자 ID: {}의 외부 시스템 정보 없음 (응답: {})",
                    participant.getId(), response);
            return;
        }

        eventJoinService.updateExternalId(participant, response.getExternalId());
    }
} 
