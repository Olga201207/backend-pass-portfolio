package ding.co.backendportfolio.chapter5._3_data_processing;

import org.springframework.stereotype.Component;

@Component
public class SensitiveWordCheckApiClient {

    // 부정어 포함 여부 확인 (더미) api 호출 서비스
    /**
     * - 비용: 10 (외부 api 호출이라는 특성으로 높게 설정)
     * - 선택도: 0.33 (근거 없이 그냥 임의로 가정)
     * => FilterOverhead: 10 * 0.33 = 3.3
     */
    public boolean containsNegativeWords(Post post) {
        FilterUtil.simulateCost(10);
        return FilterUtil.simulateSelectivity(0.33);
    }
}
