package ding.co.backendportfolio.chapter5._4_async_operation;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

@Slf4j
public class Basic {

    // 동기(Synchronously) & 블로킹(blocking)
    @Test
    public void makeBreadSynchronously() {
        System.out.println("1. 반죽하기");
        System.out.println("2. 오븐에 빵 굽기");
        try {
            Thread.sleep(1000); // 오븐에 굽는 시간
        } catch (InterruptedException e) {
        }
        System.out.println("!!! 빵 굽기 완료!!!");

        System.out.println("=== 빵 먹기 전 ===");
        System.out.println("3. 빵 먹기");
    }

    // 비동기(Asynchronously)
    @Test
    public void makeBreadAsynchronously() {
        // 현재 스레드가 출력합니다. - main-thread-01
        log.info("1. 반죽하기");
        log.info("2. 오븐에 빵 굽기");
        // '오븐에 빵 굽기' 작업을 별도 스레드로 시작 -  async-thread-02
        CompletableFuture<Void> completableFuture = CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(1000); // 오븐에 굽는 작업
                    } catch (InterruptedException e) {
                    }
                })
                // "빵 굽기" 가 완료되면 할 일을 정의합니다.
                .thenRun(() -> log.info("!!! 빵 굽기 완료!!!"));

        // 현재 스레드가 빵 굽기를 기다리지 않고, 아래의 코드를 출력합니다.
        // main-thread-01가 안 기다리고 바로 일함. -> Non Blocking
        log.info("=== 빵 먹기 전 ===");

        // 현재 스레드가 빵이 구워지기를 기다립니다. '오븐에 빵 굽기' 완료가되어야만 넘어갈 수 있음
        // main-thread-01 이 기다려야 하는 상황. 기다려야 해!!!! -> 메인스레드가 막힘
        // -> Blocking
        completableFuture.join();

        // main-thread-01 수행
        log.info("3. 빵 먹기");
    }

    // completableFuture: 단순한 비동기 실행을 넘어서, 손쉽게 이 비동기 작업간의 흐름을 조합하고 결과를 조작할 수 있음
    @Test
    public void completableFutureExample() {
        // 1) supplyAsync: 별도 스레드에서 비동기로 실행 (값 반환)
        // 비동기적으로 작업 수행 후, 반환 값을 가짐
        // 즉, future 라는 변수에는 내부 작업 수행 결과를 갖고 있음
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                // 오래 걸리는 작업 시뮬레이션
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
            return "빵";
        });

        // 2) thenApply: 이전 단계 결과를 받아서 추가 작업
        // "빵" -> "빵 + 잼" -> "빵 + 잼 + 버터"
        CompletableFuture<String> chainedFuture = future
                .thenApply(bread -> bread + " + 잼")
                .thenApply(breadWithJam -> breadWithJam + " + 버터");

        // 빵먹기 전에 해야 하는 행동들 추가 구현
        // 중간에서 cpu 바운딩 된 액션들을 여기에서 동작
        // 여기에서는 메인 스레드를 안 쉬게 만듦 (논 블로킹)

        // 3) join: 최종 결과를 얻을 때까지 기다림 (블로킹)
        String result = chainedFuture.join();
        System.out.println("결과: " + result); // 결과: 빵 + 잼 + 버터
    }
}
