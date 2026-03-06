package ding.co.backendportfolio.chapter5._1_n_plus_one.one_to_many_eager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Service
public class HotelEagerService {

    private final HotelEagerRepository hotelEagerRepository;

    @Transactional(readOnly = true)
    public void findById(Long id) {
        HotelEager hotelEager = hotelEagerRepository.findById(id).orElseThrow();
        System.out.println(hotelEager.getRoomEagers().getClass()); // class org.hibernate.collection.spi.PersistentBag
        hotelEager.getRoomEagers().size();
    }

    @Transactional(readOnly = true)
    public List<HotelEager> getAvailableHotelEagers() {
        List<HotelEager> hotelEagers = hotelEagerRepository.findAll();

        return hotelEagers.stream()
                .filter(hotelEager -> !hotelEager.getRoomEagers().isEmpty())
                .toList();
    }

    // JPA sql
    // 1. EntityManager.find() / CrudRepository.findById()
    // 2. findAll JPQL을 hibernate SQL로 변환해서 실행하는 방식
}
