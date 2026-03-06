package ding.co.backendportfolio.chapter5improved._1_n_plus_one;

import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.batch_size.HotelBatchSize;
import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.batch_size.HotelBatchSizeRepository;
import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.batch_size.HotelBatchSizeService;
import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.batch_size.RoomBatchSize;
import ding.co.backendportfolio.config.IntegrationTest;
import ding.co.backendportfolio.util.TestLogUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
public class BatchSizeTest {

    @Autowired
    private HotelBatchSizeRepository hotelBatchSizeRepository;

    @Autowired
    private HotelBatchSizeService hotelBatchSizeService;

    @AfterEach
    void cleanUp() {
        TestLogUtil.CleanStart();

        hotelBatchSizeRepository.deleteAll();

        TestLogUtil.CleanEnd();
    }

    @DisplayName("OneToMany Batch Size - getAvailableHotels")
    @Test
    void testGetHotels() {
        // given
        TestLogUtil.setUpStart();

        // A, B, C, D, E 호텔의 방을 조회해줘
        // Lazy loading -> A의 방을 조회하고 올게 B의 방을 조회하고 올게 C의 방을 조회하고 올게....
        // D의 방을 조회하고 올게, E의 방을 조회하고 올게 -> 5번의 쿼리가 나가는 수행방식
        // batch size (같은 쿼리( 개수)를 batch size 개수만큼 묶어서 나가도록)
        // 예시
        // batch size(2) A,B의 방을 조회하고 올게 -> C,D의 방을 조회하고 올게
        // E의 방을 조회하고 올게 -> 즉 총 3번의 쿼리만 나가도록 설정
        // batch size의 단점.
        // batch size로 지정한 만큼 필요 없어도, 그만큼씩 항상 로딩한다.

        createHotelBatchSize("A", 5);
        createHotelBatchSize("B", 5);
        createHotelBatchSize("C", 5);
        createHotelBatchSize("D", 5);
        createHotelBatchSize("E", 5);
        TestLogUtil.setUpEnd();

        hotelBatchSizeService.getAvailableHotels();
    }

    @DisplayName("OneToMany Batch Size - printTwoRoomName")
    @Test
    void printTwoRoomName() {
        // given
        TestLogUtil.setUpStart();
        createHotelBatchSize("A", 5);
        createHotelBatchSize("B", 5);
        createHotelBatchSize("C", 5);
        createHotelBatchSize("D", 5);
        createHotelBatchSize("E", 5);
        TestLogUtil.setUpEnd();

        // when
        hotelBatchSizeService.printTwoRoomName();
    }

    private HotelBatchSize createHotelBatchSize(String hotelName, int roomCount) {
        HotelBatchSize hotelBatchSize = new HotelBatchSize("Hotel " + hotelName);
        for (int i = 1; i <= roomCount; i++) {
            RoomBatchSize roomBatchSize = new RoomBatchSize("Room " + i);
            hotelBatchSize.addRoomBatchSize(roomBatchSize);
        }
        return hotelBatchSizeRepository.save(hotelBatchSize);
    }
}
