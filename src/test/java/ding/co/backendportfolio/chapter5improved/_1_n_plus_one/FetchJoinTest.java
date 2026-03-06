package ding.co.backendportfolio.chapter5improved._1_n_plus_one;

import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.lazy_and_fetch_join.HotelImproved;
import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.lazy_and_fetch_join.HotelImprovedRepository;
import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.lazy_and_fetch_join.HotelImprovedService;
import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.lazy_and_fetch_join.RoomImproved;
import ding.co.backendportfolio.config.IntegrationTest;
import ding.co.backendportfolio.util.TestLogUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class FetchJoinTest {

    @Autowired
    private HotelImprovedRepository hotelImprovedRepository;

    @Autowired
    private HotelImprovedService hotelImprovedService;

    @AfterEach
    void cleanUp() {
        TestLogUtil.CleanStart();

        hotelImprovedRepository.deleteAll();

        TestLogUtil.CleanEnd();
    }

    // fetch join 방식은 1번의 쿼리로 1:N 관계의 결과값을 가져올 수 있다.
    // 하지만 제약사항이 있다
    // 1. 중복 row 유발 가능성
    //  ex) hotel에 대한 room이 각각 2개인 상황에서,
    //      Hotel A    Room A1
    //      Hotel A    Room A2
    //      Hotel B    Room B1
    //      Hotel B    Room B2 ...
    // 총 10개의 row를 가져오게 됨
    // 이 쿼리의 실행 결과로 10개의 row를 가져오게 되면, jpa는 1차 캐시를 통해 호텔 엔티티를 중복 생성하지 않도록 처리한다.
    // 즉, 첫 번째로  hotel A를 만들었다면, 다음 row에서 hotel A가 나오더라도 1차 캐시에 있다고 판단해서 같은 entity를 사용
    //
    //      Hotel A(id=1)    Room A1(r-1)
    //      Hotel A(id=1)    Room A2(r-2) => 여기에서 두 번째 A row의 id 값을 새롭게 2라는 entity를 1차 캐시에 생성하는 게 아니라, 1인 상태로 동일하게 가져가는 방식
    //      Hotel B(id=2)    Room B1(r-3)
    //      Hotel B(id=2)    Room B2(r-4) ...
    // db 차원에서 중복 데이터가 있는 모든 row를 처리하게 되면, entity cache 내에서는 중복 필요 없에 필요한 수의 entity 개수만 memory에 올라가는 상황이 발생
    // 1. jpa(정확히는 hibernate)는 첫 번째 row부터 하나씩 읽어나가면서
    // 2. 1차 캐시에 이미 존재하는 식별자(pk)가 있으면 기존 엔티티 재사용
    // 3. 이미 존재하면 -> 재활용 (중복 엔티티 생성 x)

    @DisplayName("Fetch Join 으로 조회")
    @Test
    void testGetHotelsImproved() {
        // given
        TestLogUtil.setUpStart();
        createHotelImproved("A", 2);
        createHotelImproved("B", 2);
        createHotelImproved("C", 2);
        createHotelImproved("D", 2);
        createHotelImproved("E", 2);
        TestLogUtil.setUpEnd();

        hotelImprovedService.getAvailableHotelImproveds();
    }

    private HotelImproved createHotelImproved(String hotelName, int roomCount) {
        HotelImproved hotelImproved = new HotelImproved("Hotel " + hotelName);
        for (int i = 1; i <= roomCount; i++) {
            RoomImproved roomImproved = new RoomImproved("Room " + i);
            hotelImproved.addRoomImproved(roomImproved);
        }
        return hotelImprovedRepository.save(hotelImproved);
    }
}
