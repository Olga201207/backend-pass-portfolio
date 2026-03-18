package ding.co.backendportfolio.chapter5improved._4_async_operation;

import ding.co.backendportfolio.chapter5._4_async_operation.MovieDetailResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chapter5improved/movies")
@RequiredArgsConstructor
public class MovieImprovedController {

    private final MovieImprovedService movieImprovedService;

    // After: 비동기(병렬) 처리
    @GetMapping("/{movieId}")
    public ResponseEntity<MovieDetailResult> getMovieDetail(
            @PathVariable Long movieId,
            @RequestParam(defaultValue = "1") Long userId) {
        return ResponseEntity.ok(movieImprovedService.getMovieDetail(userId, movieId));
    }
}
