package ding.co.backendportfolio.chapter5._3_data_processing;

import java.util.concurrent.ThreadLocalRandom;

// 비용과 선택도를 시뮬레이션 할 수 있도록 하는 util 클래스
public class FilterUtil {

    // 비용을 시뮬레이션하는 메서드
    // 어느 정도로 시간이 걸렸느냐를 입력되는 ms를 기반으로 thread를 잠재움
    public static void simulateCost(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {

        }
    }

    // 랜덤한 벨류를 뽑아서 인수(value)보다 작은지 아닌지를 return
    // 특정 확률에 따라서 선택도가 몇이 되는지를 표현함;
    // 확률적으로 선택도를 몇 퍼센트나 걸러주는지
    public static boolean simulateSelectivity(double value) {
        return ThreadLocalRandom.current().nextDouble() < value;
    }
}
