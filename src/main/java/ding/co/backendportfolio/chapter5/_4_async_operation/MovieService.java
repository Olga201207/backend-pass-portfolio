package ding.co.backendportfolio.chapter5._4_async_operation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Service
public class MovieService {

    private final MovieRepository movieRepository;
    private final MovieBookingClient bookingClient;
    private final MovieRecommendClient recommendClient;
    private final MovieRankingClient rankingClient;
    private final MovieInterestEmitter interestEmitter;
    private final MoviePushNotificationClient moviePushNotificationClient;

    // 영화 상세 조회
    public MovieDetailResult getMovieDetail(Long userNo, Long movieId) {
        Movie movie = movieRepository.findById(movieId).orElseThrow();

        int ranking = rankingClient.getRanking(movieId);
        boolean isAvailable = bookingClient.isAvailable(movieId);
        List<Long> recommendedMovieIds = recommendClient.getRecommendedMovieIds(userNo);

        // 사용자가 해당 영화를 조회했다는 사실을 내부적으로 기록하여, 향후 추천 시스템에 활용하려는 데이터 수집 목적의 로직 (더미 메서드긴 함)
        // 이 메서드의 경우, 호출한 이후 응답값을 따로 받지 않음
        // 즉, 이 작업의 결과는 사용자의 응답과 아무런 연관이 없음
        // 하지만, 현재 동기방식으로 실행되고 있기 때문에 사용자는 이 작업이 끝날 때까지 기다려야만 전체 api 응답을 받을 수 있음
        interestEmitter.emitUserInterest(userNo, movieId);

        return MovieDetailResult.builder()
                .movieId(movie.getId())
                .title(movie.getTitle())
                .ranking(ranking)
                .isAvailable(isAvailable)
                .recommendedMovieIds(recommendedMovieIds)
                .build();
    }

    // 예매 확정 푸시 알림 전송
    public void sendBookingConfirmationPush(Map<Long, Long> bookingIdByUserNo) {
        bookingIdByUserNo.forEach((userNo, bookingId) -> {
            moviePushNotificationClient.sendBookingConfirmation(userNo, bookingId);
        });
    }
}
