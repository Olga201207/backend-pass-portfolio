package ding.co.backendportfolio.chapter5improved._4_async_operation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MovieImprovedInterestEmitter {

    // 이 코드를 비동기적으로 수행하기 위해,
    // 1. async 어노테이션을 붙여서 다음의 메서드가 호출되면,
    // 2. 이 메서드는 async executor에 등록되어 있는 스레드풀 내의 스레드를 사용해서 비동기적으로 수행
    //    => @Async("asyncExecutor") 적용해서 AsyncConfig의 bean 등록된 asyncExecutor 스레드풀 내의 스레드를 사용
    @Async("asyncExecutor")
    public void emitUserInterest(Long userNo, Long movieId) {
        try {
            Thread.sleep(200L);
        } catch (InterruptedException e) {
        }
    }
}
