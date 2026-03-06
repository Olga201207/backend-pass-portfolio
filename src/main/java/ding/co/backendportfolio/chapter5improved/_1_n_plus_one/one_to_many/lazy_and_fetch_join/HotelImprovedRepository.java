package ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.lazy_and_fetch_join;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface HotelImprovedRepository extends JpaRepository<HotelImproved, Long> {
    // fetch join은 JPA 표준에서 지원하는 jpql 문법임
    // sql의 조인을 jpql 내에서 도입한 개념
    // 즉, join을 jpql에서 다음과 같이 구현을 한 거임: JOIN FETCH
    // 아래 쿼리 의미
    // HotelImproved 객체의 roomImproveds 멤버변수를 join query를 통해서 가져오라는 의미
    // 즉, roomImproveds 라고 하는 연관관계를 미리 가져와라 라는 의미
    // lazy로 설정한 연관 관계도 fetch join을 사용하면, 한 번의 sql로 수행하여 N+1 문제를 해결할 수 있게 된다.
    @Query("SELECT h FROM HotelImproved h JOIN FETCH h.roomImproveds")
    List<HotelImproved> findAllWithRooms();
}
