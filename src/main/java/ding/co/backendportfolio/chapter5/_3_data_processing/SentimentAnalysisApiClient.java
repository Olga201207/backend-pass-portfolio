package ding.co.backendportfolio.chapter5._3_data_processing;

import org.springframework.stereotype.Component;

@Component
public class SentimentAnalysisApiClient {

    // 긍정적 감정 분석 결과 외부 (더미) api 호출 서비스
    /**
     * - 비용: 10
     * - 선택도: 0.5
     * => FilterOverhead: 10 * 0.5 = 5
     */
    public boolean isPositiveSentiment(Post post) {
        FilterUtil.simulateCost(10);
        return FilterUtil.simulateSelectivity(0.5);
    }
}
