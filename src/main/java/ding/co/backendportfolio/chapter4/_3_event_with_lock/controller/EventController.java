package ding.co.backendportfolio.chapter4._3_event_with_lock.controller;

import ding.co.backendportfolio.chapter2.service.MemberService;
import ding.co.backendportfolio.chapter4._3_event_with_lock.entity.EventWithLock;
import ding.co.backendportfolio.chapter4._3_event_with_lock.facade.NamedLockEventFacade;
import ding.co.backendportfolio.chapter4._3_event_with_lock.facade.OptimisticLockEventFacade;
import ding.co.backendportfolio.chapter4._3_event_with_lock.repository.EventWithLockRepository;
import ding.co.backendportfolio.chapter4._3_event_with_lock.service.EventWithLockService;
import ding.co.backendportfolio.chapter4._4_event_with_external.repository.EventWithLockParticipantRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/chapter4/events")
@RequiredArgsConstructor
public class EventController {
    private final EventWithLockService eventWithLockService;
    private final NamedLockEventFacade namedLockEventFacade;
    private final OptimisticLockEventFacade optimisticLockEventFacade;
    private final MemberService memberService;
    private final EventWithLockRepository eventWithLockRepository;
    private final EventWithLockParticipantRepository participantRepository;

    // 테스트용 이벤트 생성
    @PostMapping("/setup")
    public ResponseEntity<Map<String, Long>> setup(
            @RequestParam(defaultValue = "30") int maxParticipants) {
        EventWithLock event = EventWithLock.builder()
                .name("선착순 이벤트")
                .description("동시성 테스트용 이벤트")
                .eventDate(LocalDateTime.now().plusDays(7))
                .maxParticipants(maxParticipants)
                .build();
        eventWithLockRepository.save(event);
        return ResponseEntity.ok(Map.of("eventId", event.getId()));
    }

    // 테스트용 이벤트 리셋 (참여자 삭제 + currentParticipants=0)
    @PostMapping("/{eventId}/reset")
    @Transactional
    public ResponseEntity<Void> reset(@PathVariable Long eventId) {
        EventWithLock event = eventWithLockRepository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException("이벤트를 찾을 수 없습니다."));
        participantRepository.deleteByEvent(event);
        event.reset();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{eventId}/join/optimistic")
    public ResponseEntity<Void> joinEventOptimistic(
            @PathVariable Long eventId,
            @AuthenticationPrincipal User user) throws InterruptedException {
        Long memberId = memberService.getCurrentMember(user.getUsername()).getId();
        optimisticLockEventFacade.joinEvent(eventId, memberId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{eventId}/join/pessimistic")
    public ResponseEntity<Void> joinEventPessimistic(
            @PathVariable Long eventId,
            @AuthenticationPrincipal User user) {
        Long memberId = memberService.getCurrentMember(user.getUsername()).getId();
        eventWithLockService.joinEventPessimistic(eventId, memberId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{eventId}/join/named")
    public ResponseEntity<Void> joinEventNamed(
            @PathVariable Long eventId,
            @AuthenticationPrincipal User user) throws InterruptedException {
        Long memberId = memberService.getCurrentMember(user.getUsername()).getId();
        namedLockEventFacade.joinEvent(eventId, memberId);
        return ResponseEntity.ok().build();
    }
} 