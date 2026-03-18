package ding.co.backendportfolio.chapter5._2_bulk_operation;

import ding.co.backendportfolio.chapter5improved._2_bulk_operation.BulkOperationImprovedService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.IntStream;

@RestController
@RequestMapping("/api/chapter5/bulk")
@RequiredArgsConstructor
public class BulkController {

    private static final String[] STATIONS = {
            "강남", "선릉", "역삼", "삼성", "잠실", "건대입구", "홍대입구", "신촌", "이대", "합정"
    };

    private final BulkOperationService bulkOperationService;
    private final BulkOperationImprovedService bulkOperationImprovedService;

    // Before: JPA saveAll (1,440번 개별 INSERT)
    @PostMapping("/individual")
    public ResponseEntity<String> insertIndividual(@RequestParam(defaultValue = "1440") int count) {
        List<SubwayStats> statsList = buildStatsList(count);
        bulkOperationService.bulkInsert(statsList);
        return ResponseEntity.ok("Inserted " + count + " rows individually");
    }

    // After: JdbcTemplate batchUpdate (단일 배치)
    @PostMapping("/batch")
    public ResponseEntity<String> insertBatch(@RequestParam(defaultValue = "1440") int count) {
        List<SubwayStats> statsList = buildStatsList(count);
        bulkOperationImprovedService.bulkInsertImproved(statsList);
        return ResponseEntity.ok("Inserted " + count + " rows as batch");
    }

    // 측정 전 초기화
    @DeleteMapping
    public ResponseEntity<String> deleteAll() {
        bulkOperationImprovedService.bulkDeleteImproved();
        return ResponseEntity.ok("Deleted all subway stats");
    }

    private List<SubwayStats> buildStatsList(int count) {
        LocalDateTime base = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        return IntStream.range(0, count)
                .mapToObj(i -> new SubwayStats(
                        STATIONS[i % STATIONS.length],
                        base.plusMinutes(i)))
                .toList();
    }
}
