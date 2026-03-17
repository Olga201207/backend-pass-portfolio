package ding.co.backendportfolio.chapter6_improved._3_remote_redis_real_example;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedLockProvider {

    private final RedisTemplate<String, String> redisTemplate;

    // setIfAbsent: key가 없는 경우에만 동작(redis에 key:value 설정) 수행
    // "locked": 아무 의미 없는 값임, 이 값이 존재하면 락을 점유할 수 없음
    public boolean tryLock(String key, long timeoutMs) {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(key, "locked", timeoutMs, TimeUnit.MILLISECONDS);
        return Boolean.TRUE.equals(success);
    }

    public void releaseLock(String key) {
        redisTemplate.delete(key);
    }
}

