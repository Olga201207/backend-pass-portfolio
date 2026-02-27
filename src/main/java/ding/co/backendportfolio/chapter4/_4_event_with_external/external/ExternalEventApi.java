package ding.co.backendportfolio.chapter4._4_event_with_external.external;

import ding.co.backendportfolio.chapter4._4_event_with_external.external.model.ExternalEventResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.UUID;

// inter park api 와 비슷한 기능
// 실제로 콘서트를 예약하는 것은 이 api(ExternalEventApi)를 통해서 예약을 하고
// 우리 서버(이 프로젝트)에서는 이 사람이 중계로 예약했다라는 데이터를 남겨놓는 기능 제공
@Component
@Slf4j
public class ExternalEventApi {
    private static final Random random = new Random();

    // 특정 이벤트에 특정 멤버가 예약을 하고 예약 결과(성공 실패 여부)를 반환받는다고 환경을 가정한 것(랜덤값으로 임의 반환값 설정되어있음)
    public ExternalEventResponse registerParticipant(Long eventId, Long memberId, String eventName) {
        try {
            Thread.sleep(random.nextInt(500, 1500));
            return ExternalEventResponse.builder()
                    .success(true)
                    .externalId(UUID.randomUUID().toString())
                    .errorMessage(null)
                    .build();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // interpark(외부 api 서버)에 eventid와 memberid 로 externalId(를 포함한 data)를 요청하는 api
    public ExternalEventResponse getParticipantInfo(Long eventId, Long memberId) {
        try {
            Thread.sleep(100);
            return ExternalEventResponse.builder()
                    .success(true)
                    .externalId(UUID.randomUUID().toString())
                    .errorMessage(null)
                    .build();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
