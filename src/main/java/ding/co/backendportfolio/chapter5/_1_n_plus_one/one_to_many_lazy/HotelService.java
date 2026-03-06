package ding.co.backendportfolio.chapter5._1_n_plus_one.one_to_many_lazy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class HotelService {

    private final HotelRepository repository;

    @Transactional(readOnly = true)
    public void findById(Long id) {
        Hotel hotel = repository.findById(id).orElseThrow();
        System.out.println(hotel.getRooms().getClass()); // class org.hibernate.collection.spi.PersistentBag
        hotel.getRooms().size();
    }

    @Transactional(readOnly = true)
    public List<Hotel> getAvailableHotels() {
        List<Hotel> hotels = repository.findAll();

        // hotel.getRooms().isEmpty() 실행하는 순간 proxy가 db에 접근해서 해당 hotel id 에 대한 room entity들을 조회함
        // 즉, 지연 로딩된 연관 컬렉션에 대한 반복 접근이 일어나서, N+1 (hotel내의 N개의 room, 1개의 hotel)문제가 발생하게 된 것
        // 호텔을 순회하면서 지연로딩을 적용한 rooms 컬렉션 -> 해당 data가 필요할 때, N + 1 문제가 발생
        // 정리
        // 지연 로딩을 사용하는 연관 컬렉션에 대해서, 루프를 돌며 접근함
        // 각 접근마다 select 쿼리가 발생하기 때문에, N + 1 문제가 유발됨
        return hotels.stream()
                .filter(hotel -> !hotel.getRooms().isEmpty())
                .collect(Collectors.toList());
    }
}
