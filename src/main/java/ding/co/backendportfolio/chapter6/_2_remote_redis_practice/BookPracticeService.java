package ding.co.backendportfolio.chapter6._2_remote_redis_practice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ding.co.backendportfolio.chapter6._1_practice.Book;
import ding.co.backendportfolio.chapter6._1_practice.BookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class BookPracticeService {

    private static final String CACHE_KEY_PREFIX = "remote-cache-practice";

    private final BookRepository bookRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    // 직렬화(Serialize): 특정 객체를 문자열로 변환하는 과정
    // 역직렬화(Deserialize): 문자열을 특정 객체로 변환하는 과정
    // redis에 저장가능한 자료형은 문자열
    // application -> redis에 저장: 직렬화 필요
    // redis -> application으로 값 가져와서 객체로 변환: 역직렬화
    public Book findBookById(Long id) throws JsonProcessingException {
        String cacheKey = CACHE_KEY_PREFIX + ":" + id;

        String serialized = redisTemplate.opsForValue().get(cacheKey);
        if (serialized != null) {
            log.info(">> 1. 캐시 HIT - 캐시 값 반환");
            return objectMapper.readValue(serialized, Book.class);
        }

        log.info(">> 1. 캐시 MISS");

        Book book = bookRepository.findById(id).orElseThrow();
        log.info(">> 2. 캐시에 없어서 DB에서 조회");

        // 직렬화(object -> string)
        redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(book));
        log.info(">> 3. 캐시에 저장");

        log.info(">> 4. DB에서 조회 후 캐시에 저장하고나서 반환");
        return book;
    }
}
