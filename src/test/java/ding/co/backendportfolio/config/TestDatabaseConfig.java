package ding.co.backendportfolio.config;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;

@TestConfiguration
public class TestDatabaseConfig {
    
    @Container
    private static final MySQLContainer<?> mysqlContainer;

    static {
        mysqlContainer = new MySQLContainer<>("mysql:8.0.33")
                .withDatabaseName("testdb")
                .withUsername("test")
                .withPassword("test");
        mysqlContainer.start();

        // A) 기본 설정 + rewriteBatchedStatements 옵션
        String originalJdbcUrl = mysqlContainer.getJdbcUrl() + "?rewriteBatchedStatements=true";

        // TODO: BulkInsert 모니터링 - 아래 주석을 해제해야함
        // jdbc에서 만드는 커넥션들은 sql을 기록(profileSQL)하고 그리고 어느 로거(logger)에다 던져줄거고, 어느 쿼리 사이즈만큼 기록(maxQuerySizeToLog)을 해줄거야
//        originalJdbcUrl = originalJdbcUrl + "&profileSQL=true&logger=Slf4JLogger&maxQuerySizeToLog=2147483647";

        System.setProperty("spring.datasource.url", originalJdbcUrl);
        System.setProperty("spring.datasource.username", mysqlContainer.getUsername());
        System.setProperty("spring.datasource.password", mysqlContainer.getPassword());
    }

    @Bean
    public MySQLContainer<?> mySQLContainer() {
        return mysqlContainer;
    }

    @PreDestroy
    public void stop() {
        if (mysqlContainer != null && mysqlContainer.isRunning()) {
            mysqlContainer.stop();
        }
    }
} 
