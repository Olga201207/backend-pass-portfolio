package ding.co.backendportfolio.chapter4._3_event_with_lock.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.LocalDateTime;

@Entity
@Table(name = "ch4_event_with_lock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventWithLock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String description;
    private LocalDateTime eventDate;
    private int maxParticipants;
    private int currentParticipants;

    // 낙관적 락을 사용할 때, 사용되는 필드
    // 동시성을 제어하는 방식으로 버전 번호를 활용해서 데이터 변경 충돌을 감지하도록 구현
    // spring 과 jpa 내에서는 버전 어노테이션(@Version)을 통해서 구현 가능
    // 이 버전 필드는 엔티티가 수정될 때마다 자동으로 증가하는 구조
    @Version
    private Long version;

    // 낙관적 락 상황 예시
    // event(id = 1, current_participants = 0, version = 100)
    // Transaction 1
    // UPDATE event SET current_participants += 1 and version += 1 where id = 1 and version = 100;
    // => event(id = 1, current_participants = 1, version = 101)
    // Transaction 2
    // UPDATE event SET current_participants += 1 where id = 1 and version = 100;
    //   => updatedRows = 0 -> 다른 transaction이 해당 버전의 record에 update를 수행함
    //   => version 값이 달라서 수행 안됨
    //   => version 값 올려서 재시도 수행해야함 (구현해야 함)
    // UPDATE event SET current_participants += 1 and version += 1 where id = 1 and version = 101;
    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    public EventWithLock(String name, String description, LocalDateTime eventDate, int maxParticipants) {
        this.name = name;
        this.description = description;
        this.eventDate = eventDate;
        this.maxParticipants = maxParticipants;
        this.currentParticipants = 0;
    }

    public void increaseParticipants() {
        if (this.currentParticipants >= this.maxParticipants) {
            throw new RuntimeException("최대 참가 인원을 초과했습니다.");
        }
        this.currentParticipants++;
    }
} 
