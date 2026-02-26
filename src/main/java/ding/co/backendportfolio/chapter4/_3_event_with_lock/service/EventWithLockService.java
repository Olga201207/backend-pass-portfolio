package ding.co.backendportfolio.chapter4._3_event_with_lock.service;

import ding.co.backendportfolio.chapter2.entity.Member;
import ding.co.backendportfolio.chapter2.repository.MemberRepository;
import ding.co.backendportfolio.chapter4._3_event_with_lock.entity.EventWithLock;
import ding.co.backendportfolio.chapter4._3_event_with_lock.entity.EventWithLockParticipant;
import ding.co.backendportfolio.chapter4._3_event_with_lock.repository.EventWithLockRepository;
import ding.co.backendportfolio.chapter4._4_event_with_external.repository.EventWithLockParticipantRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventWithLockService {
    private final EventWithLockRepository eventRepository;
    private final EventWithLockParticipantRepository participantRepository;
    private final MemberRepository memberRepository;


    // 낙관적 락
    @Transactional
    public void joinEventOptimistic(Long eventId, Long memberId) {
        // 이벤트 객체의 version 정보를 기억 (이하 version 값이 100 이라고 가정)
        // 1. 이벤트 정보 가져옴
        EventWithLock event = eventRepository.findByIdWithOptimisticLock(eventId)
                .orElseThrow(() -> new EntityNotFoundException("이벤트를 찾을 수 없습니다."));
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다."));

        // 2. 이벤트 정보를 확인하고, version = 100 인지 학인
        // 2-1. 100이 아니라면 에러 반환
        //      => 자동으로 재시도하는 로직이 코드상에 구현이 되어있어야 함
        //      => 이 프로젝트에서는 OptimisticLockEventFacade 에 구현되어있음
        // 2-2. 이벤트 버전이 일치한다면, 참가자 수를 늘리기
        // saveAndFlush 적용 이유: eventParticipants의 eventId 외래키로 인해 공유락이 record 단위로 설정됨, event 객체(record)의 특정 필드 값 update해야함(x - lock 필요). 다른 놈(동일 메서드 실행하는 다른 transaction)이 공유락 갖고 안놓아주고 있어서 dead lock 발생
        // jpa 특성상 insert 작업이 먼저 실행되기 때문에, 명시적으로 update(increaseParticipants) 가 먼저 실행 되도록 saveAndFlush 적용
        event.increaseParticipants();
        eventRepository.saveAndFlush(event);

        EventWithLockParticipant participant = EventWithLockParticipant.builder()
                .event(event)
                .member(member)
                .build();
        participantRepository.save(participant);
    }

    // 비관적 락
    // 비관적 락의 단점: 락(x lock 등; db의 락 종류)을 설정할 때, db의 리소스를 사용
    // 장점: 물론, 충돌(동시성 관련)이 많이 발생할 가능성이 높은 경우에는 비관적 락일 사용하는게 효율적임(충돌나면 롤백되는 횟수가 상당할 것이기 때문)
    @Transactional
    public void joinEventPessimistic(Long eventId, Long memberId) {
        // 1. select & 배타락(x lock) 설정
        // 이벤트를 배타락으로 가져옴. -> 그럼 다른 트랜잭션에서는 배타락을 설정 못하기 때문에 대기함.
        // 이벤트를 찾아올 때는 비관락 설정해서 찾아온다. 즉, select 수행 동안 다른 transaction에서는 (락 설정 없는) select를 제외한 작업을 수행할 수 없음(기다려야 함)
        // select 쿼리는 호출 시점에 바로 수행되고, write 계열들은 transaction(메서드)이 끝났을 때 한꺼번에 수행된다.
        // 즉, select 문이 write 쿼리들보다 먼저 수행됨. (당연한건가?)
        EventWithLock event = eventRepository.findByIdWithPessimisticLock(eventId)
                .orElseThrow(() -> new EntityNotFoundException("이벤트를 찾을 수 없습니다."));

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다."));

        // 2. update
        // 이벤트 참가자 수를 늘린 다음에
        event.increaseParticipants();
        // eventRepository.saveAndFlush(event); 를 안하는 이유
        // findByIdWithPessimisticLock 에서 비관적 락을 설정해놨기 때문.
        // 이 비관적 락은 이 메서드(transaction)가 끝나기(commit)까지 풀리지 않는다. -> 다른 트랜잭션에서 write 작업 수행하려 해도 불가능(대기해야함)

        // 이벤트 참가자 엔티티를 추가하고 있음
        EventWithLockParticipant participant = EventWithLockParticipant.builder()
                .event(event)
                .member(member)
                .build();

        // 3. insert
        participantRepository.save(participant);
    }

    // TODO: Named Lock 을 위해 아래 주석을 해제해야함
    //    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void joinEventWithNamedLock(Long eventId, Long memberId) {
        EventWithLock event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException("이벤트를 찾을 수 없습니다."));
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다."));

        event.increaseParticipants();
        eventRepository.saveAndFlush(event);

        EventWithLockParticipant participant = EventWithLockParticipant.builder()
                .event(event)
                .member(member)
                .build();
        participantRepository.save(participant);
    }
} 
