package ding.co.backendportfolio.chapter5._3_data_processing;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class FilterService {

    private final SentimentAnalysisApiClient sentimentAnalysisApiClient;
    private final SensitiveWordCheckApiClient sensitiveWordCheckApiClient;

    /**
     * 1. 감정 분석: 비용 10, 선택도 0.5 => FilterOverhead 5
     * 2. 부정적 단어 포함 체크: 비용 10, 선택도 0.33 => FilterOverhead 3.3
     * 3. 포스트 타입 체크: 비용 1, 선택도 0.33 => FilterOverhead 0.33
     */
    public void badFilter(List<Post> posts) {
        posts.stream()
                .filter(post -> sentimentAnalysisApiClient.isPositiveSentiment(post))       // 긍정적 감정 분석 api 호출
                .filter(post -> sensitiveWordCheckApiClient.containsNegativeWords(post))    // 부정적 단어 포함 체크 api 호출
                .filter(post -> post.isVideoType())                                         // 포스트 타입 체크 기능
                .collect(Collectors.toList());
    }
}
