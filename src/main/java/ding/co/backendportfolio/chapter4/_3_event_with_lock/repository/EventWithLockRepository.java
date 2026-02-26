package ding.co.backendportfolio.chapter4._3_event_with_lock.repository;

import ding.co.backendportfolio.chapter4._3_event_with_lock.entity.EventWithLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EventWithLockRepository extends JpaRepository<EventWithLock, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EventWithLock e where e.id = :id")
    Optional<EventWithLock> findByIdWithPessimisticLock(Long id);
    // select * from accounts where id = 1 for update;
    // LockModeType.PESSIMISTIC_WRITE 가 for update(x - lock; 단일 레코드) 기능을 함
    // 위의 쿼리 실행시, 배타 lock 설정되서 다른 transaction 이 해당 record에 접근 못함


    // 낙관적 락 annotation
    @Lock(LockModeType.OPTIMISTIC)
    @Query("select e from EventWithLock e where e.id = :id")
    Optional<EventWithLock> findByIdWithOptimisticLock(Long id);

    @Query(value = "SELECT GET_LOCK(:lockName, :timeoutSeconds)", nativeQuery = true)
    Integer getLock(@Param("lockName") String lockName, @Param("timeoutSeconds") int timeoutSeconds);

    @Query(value = "SELECT RELEASE_LOCK(:lockName)", nativeQuery = true)
    Integer releaseLock(@Param("lockName") String lockName);
} 
