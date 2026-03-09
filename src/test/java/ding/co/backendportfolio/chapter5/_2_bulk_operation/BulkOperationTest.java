package ding.co.backendportfolio.chapter5._2_bulk_operation;

import static org.assertj.core.api.Assertions.assertThat;

import ding.co.backendportfolio.config.IntegrationTest;
import ding.co.backendportfolio.util.TestLogUtil;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class BulkOperationTest {

    @Autowired
    private BulkOperationService bulkOperationService;

    @Autowired
    private SubwayStatsRepository repository;

    @AfterEach
    void cleanUp() {
        TestLogUtil.CleanStart();
        repository.deleteAllInBatch();
        assertThat(repository.count()).isZero();
        TestLogUtil.CleanEnd();
    }

    // insert를 1번 하는게 아닌 1440 번하게 됨
    @DisplayName("BulkInsert")
    @Test
    void testBulkInsert() {
        // when
        List<SubwayStats> data = SubwayDataProvider.createData();
        bulkOperationService.bulkInsert(data);

        // then
        TestLogUtil.assertStart();
        assertThat(repository.count()).isEqualTo(1440);
        TestLogUtil.assertEnd();
    }

    @DisplayName("BulkDelete")
    @Test
    void testBulkDelete() {
        // given
        TestLogUtil.setUpStart();
        List<SubwayStats> data = SubwayDataProvider.createData();
        List<SubwayStats> subwayStats = repository.saveAll(data);
        assertThat(subwayStats).hasSize(1440);
        TestLogUtil.setUpEnd();

        // when
        bulkOperationService.bulkDelete();

        // then
        TestLogUtil.assertStart();
        assertThat(repository.count()).isZero();
        TestLogUtil.assertEnd();
    }
}
