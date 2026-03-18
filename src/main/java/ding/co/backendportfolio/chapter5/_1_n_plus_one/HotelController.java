package ding.co.backendportfolio.chapter5._1_n_plus_one;

import ding.co.backendportfolio.chapter5._1_n_plus_one.one_to_many_lazy.Hotel;
import ding.co.backendportfolio.chapter5._1_n_plus_one.one_to_many_lazy.HotelRepository;
import ding.co.backendportfolio.chapter5._1_n_plus_one.one_to_many_lazy.HotelService;
import ding.co.backendportfolio.chapter5._1_n_plus_one.one_to_many_lazy.Room;
import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.batch_size.HotelBatchSize;
import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.batch_size.HotelBatchSizeRepository;
import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.batch_size.HotelBatchSizeService;
import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.batch_size.RoomBatchSize;
import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.entity_graph.HotelEntityGraph;
import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.entity_graph.HotelEntityGraphRepository;
import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.entity_graph.HotelEntityGraphService;
import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.entity_graph.RoomEntityGraph;
import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.lazy_and_fetch_join.HotelImproved;
import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.lazy_and_fetch_join.HotelImprovedRepository;
import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.lazy_and_fetch_join.HotelImprovedService;
import ding.co.backendportfolio.chapter5improved._1_n_plus_one.one_to_many.lazy_and_fetch_join.RoomImproved;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.IntStream;

@RestController
@RequestMapping("/api/chapter5/hotels")
@RequiredArgsConstructor
public class HotelController {

    private final HotelService hotelService;
    private final HotelRepository hotelRepository;

    private final HotelImprovedService hotelImprovedService;
    private final HotelImprovedRepository hotelImprovedRepository;

    private final HotelBatchSizeService hotelBatchSizeService;
    private final HotelBatchSizeRepository hotelBatchSizeRepository;

    private final HotelEntityGraphService hotelEntityGraphService;
    private final HotelEntityGraphRepository hotelEntityGraphRepository;

    // Before: Lazy Loading (N+1 발생)
    @GetMapping("/lazy")
    public ResponseEntity<List<Hotel>> getLazyHotels() {
        return ResponseEntity.ok(hotelService.getAvailableHotels());
    }

    // After: Fetch Join (1 query)
    @GetMapping("/fetch-join")
    public ResponseEntity<List<HotelImproved>> getFetchJoinHotels() {
        return ResponseEntity.ok(hotelImprovedService.getAvailableHotelImproveds());
    }

    // After: BatchSize
    @GetMapping("/batch-size")
    public ResponseEntity<List<HotelBatchSize>> getBatchSizeHotels() {
        return ResponseEntity.ok(hotelBatchSizeService.getAvailableHotels());
    }

    // After: EntityGraph
    @GetMapping("/entity-graph")
    public ResponseEntity<List<HotelEntityGraph>> getEntityGraphHotels() {
        return ResponseEntity.ok(hotelEntityGraphService.getAvailableHotels());
    }

    // 데이터 시딩: 4종 Hotel 엔티티에 각각 hotelCount개 호텔 + 방 5개씩 생성
    @PostMapping("/setup")
    @Transactional
    public ResponseEntity<String> setup(@RequestParam(defaultValue = "100") int hotelCount) {
        IntStream.rangeClosed(1, hotelCount).forEach(i -> {
            // Lazy hotel
            Hotel hotel = new Hotel("Hotel " + i);
            IntStream.rangeClosed(1, 5).forEach(j -> hotel.addRoom(new Room("Room " + j)));
            hotelRepository.save(hotel);

            // Fetch Join hotel
            HotelImproved hotelImproved = new HotelImproved("Hotel " + i);
            IntStream.rangeClosed(1, 5).forEach(j -> hotelImproved.addRoomImproved(new RoomImproved("Room " + j)));
            hotelImprovedRepository.save(hotelImproved);

            // BatchSize hotel
            HotelBatchSize hotelBatchSize = new HotelBatchSize("Hotel " + i);
            IntStream.rangeClosed(1, 5).forEach(j -> hotelBatchSize.addRoomBatchSize(new RoomBatchSize("Room " + j)));
            hotelBatchSizeRepository.save(hotelBatchSize);

            // EntityGraph hotel
            HotelEntityGraph hotelEntityGraph = new HotelEntityGraph("Hotel " + i);
            IntStream.rangeClosed(1, 5).forEach(j -> hotelEntityGraph.addRoomEntityGraph(new RoomEntityGraph("Room " + j)));
            hotelEntityGraphRepository.save(hotelEntityGraph);
        });
        return ResponseEntity.ok("Seeded " + hotelCount + " hotels (x4 strategies)");
    }
}
