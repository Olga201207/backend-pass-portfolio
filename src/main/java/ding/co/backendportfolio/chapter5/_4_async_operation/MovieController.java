package ding.co.backendportfolio.chapter5._4_async_operation;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.stream.IntStream;

@RestController
@RequestMapping("/api/chapter5/movies")
@RequiredArgsConstructor
public class MovieController {

    private final MovieService movieService;
    private final MovieRepository movieRepository;

    // Before: 동기(직렬) 처리
    @GetMapping("/{movieId}")
    public ResponseEntity<MovieDetailResult> getMovieDetail(
            @PathVariable Long movieId,
            @RequestParam(defaultValue = "1") Long userId) {
        return ResponseEntity.ok(movieService.getMovieDetail(userId, movieId));
    }

    // 데이터 시딩
    @PostMapping("/setup")
    public ResponseEntity<String> setup(@RequestParam(defaultValue = "100") int count) {
        IntStream.rangeClosed(1, count)
                .mapToObj(i -> new Movie("Movie " + i))
                .forEach(movieRepository::save);
        return ResponseEntity.ok("Seeded " + count + " movies");
    }
}
