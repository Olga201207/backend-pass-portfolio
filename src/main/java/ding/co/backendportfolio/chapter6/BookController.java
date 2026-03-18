package ding.co.backendportfolio.chapter6;

import com.fasterxml.jackson.core.JsonProcessingException;
import ding.co.backendportfolio.chapter6._1_practice.Book;
import ding.co.backendportfolio.chapter6._1_practice.BookRepository;
import ding.co.backendportfolio.chapter6._3_remote_redis_real_example.CacheAvalancheService;
import ding.co.backendportfolio.chapter6._3_remote_redis_real_example.HotKeyService;
import ding.co.backendportfolio.chapter6_improved._3_remote_redis_real_example.ImprovedCacheAvalancheService;
import ding.co.backendportfolio.chapter6_improved._3_remote_redis_real_example.ImprovedHotKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.stream.IntStream;

@RestController
@RequiredArgsConstructor
public class BookController {

    private final HotKeyService hotKeyService;
    private final ImprovedHotKeyService improvedHotKeyService;
    private final CacheAvalancheService cacheAvalancheService;
    private final ImprovedCacheAvalancheService improvedCacheAvalancheService;
    private final BookRepository bookRepository;
    private final RedisTemplate<String, String> redisTemplate;

    // 6C Hot Key - Before (락 없음)
    @GetMapping("/api/chapter6/books/{id}")
    public ResponseEntity<Book> findBookById(@PathVariable Long id) throws JsonProcessingException {
        return ResponseEntity.ok(hotKeyService.findBookByIdWithoutLock(id));
    }

    // 6C Hot Key - After (분산 락 적용)
    @GetMapping("/api/chapter6improved/books/{id}")
    public ResponseEntity<Book> findBookByIdImproved(@PathVariable Long id) throws JsonProcessingException {
        return ResponseEntity.ok(improvedHotKeyService.findBookByIdWithLock(id));
    }

    // 6B Cache Avalanche - Before (고정 TTL)
    @GetMapping("/api/chapter6/books/{id}/avalanche")
    public ResponseEntity<Book> findBookAvalanche(@PathVariable Long id) throws JsonProcessingException {
        return ResponseEntity.ok(cacheAvalancheService.findBookByIdWithNoJitter(id));
    }

    // 6B Cache Avalanche - After (Jitter 적용)
    @GetMapping("/api/chapter6improved/books/{id}/avalanche")
    public ResponseEntity<Book> findBookAvalancheImproved(@PathVariable Long id) throws JsonProcessingException {
        return ResponseEntity.ok(improvedCacheAvalancheService.findBookByIdWithJitter(id));
    }

    // 데이터 시딩
    @PostMapping("/api/chapter6/books/setup")
    public ResponseEntity<String> setup(@RequestParam(defaultValue = "1000") int count) {
        IntStream.rangeClosed(1, count)
                .mapToObj(i -> Book.builder().name("Book " + i).isSoldOut(false).build())
                .forEach(bookRepository::save);
        return ResponseEntity.ok("Seeded " + count + " books");
    }

    // Redis 캐시 전체 삭제 (측정 전 초기화)
    @DeleteMapping("/api/chapter6/books/cache")
    public ResponseEntity<String> clearCache() {
        redisTemplate.getConnectionFactory().getConnection().flushDb();
        return ResponseEntity.ok("Redis cache cleared");
    }
}
