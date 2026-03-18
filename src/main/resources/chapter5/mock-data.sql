-- Chapter 5 Mock Data
-- Hotel + Room 시딩: 4가지 N+1 전략별 테이블에 각각 100개 호텔, 호텔당 5개 방 생성
-- Movie 시딩: 비동기 처리 시나리오용 100개 영화
-- 실행: mysql -u portfolio_user -p portfolio < src/main/resources/chapter5/mock-data.sql

-- ===== ch5_hotel + ch5_room (Lazy Loading 시나리오) =====
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS seed_ch5_hotels()
BEGIN
  DECLARE i INT DEFAULT 1;
  DECLARE j INT DEFAULT 1;
  DECLARE hotel_id BIGINT;
  WHILE i <= 100 DO
    INSERT INTO ch5_hotel (name) VALUES (CONCAT('Hotel ', i));
    SET hotel_id = LAST_INSERT_ID();
    SET j = 1;
    WHILE j <= 5 DO
      INSERT INTO ch5_room (name, hotel_id) VALUES (CONCAT('Room ', j), hotel_id);
      SET j = j + 1;
    END WHILE;
    SET i = i + 1;
  END WHILE;
END //
DELIMITER ;
CALL seed_ch5_hotels();
DROP PROCEDURE IF EXISTS seed_ch5_hotels;

-- ===== ch5_hotel_improved + ch5_room_improved (Fetch Join 시나리오) =====
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS seed_ch5_hotels_improved()
BEGIN
  DECLARE i INT DEFAULT 1;
  DECLARE j INT DEFAULT 1;
  DECLARE hotel_id BIGINT;
  WHILE i <= 100 DO
    INSERT INTO ch5_hotel_improved (name) VALUES (CONCAT('Hotel ', i));
    SET hotel_id = LAST_INSERT_ID();
    SET j = 1;
    WHILE j <= 5 DO
      INSERT INTO ch5_room_improved (name, hotel_improved_id) VALUES (CONCAT('Room ', j), hotel_id);
      SET j = j + 1;
    END WHILE;
    SET i = i + 1;
  END WHILE;
END //
DELIMITER ;
CALL seed_ch5_hotels_improved();
DROP PROCEDURE IF EXISTS seed_ch5_hotels_improved;

-- ===== ch5_hotel_batch_size + ch5_room_batch_size (BatchSize 시나리오) =====
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS seed_ch5_hotels_batch()
BEGIN
  DECLARE i INT DEFAULT 1;
  DECLARE j INT DEFAULT 1;
  DECLARE hotel_id BIGINT;
  WHILE i <= 100 DO
    INSERT INTO ch5_hotel_batch_size (name) VALUES (CONCAT('Hotel ', i));
    SET hotel_id = LAST_INSERT_ID();
    SET j = 1;
    WHILE j <= 5 DO
      INSERT INTO ch5_room_batch_size (name, hotel_batch_size_id) VALUES (CONCAT('Room ', j), hotel_id);
      SET j = j + 1;
    END WHILE;
    SET i = i + 1;
  END WHILE;
END //
DELIMITER ;
CALL seed_ch5_hotels_batch();
DROP PROCEDURE IF EXISTS seed_ch5_hotels_batch;

-- ===== ch5_hotel_entity_graph + ch5_room_entity_graph (EntityGraph 시나리오) =====
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS seed_ch5_hotels_entity_graph()
BEGIN
  DECLARE i INT DEFAULT 1;
  DECLARE j INT DEFAULT 1;
  DECLARE hotel_id BIGINT;
  WHILE i <= 100 DO
    INSERT INTO ch5_hotel_entity_graph (name) VALUES (CONCAT('Hotel ', i));
    SET hotel_id = LAST_INSERT_ID();
    SET j = 1;
    WHILE j <= 5 DO
      INSERT INTO ch5_room_entity_graph (name, hotel_entity_graph_id) VALUES (CONCAT('Room ', j), hotel_id);
      SET j = j + 1;
    END WHILE;
    SET i = i + 1;
  END WHILE;
END //
DELIMITER ;
CALL seed_ch5_hotels_entity_graph();
DROP PROCEDURE IF EXISTS seed_ch5_hotels_entity_graph;

-- ===== ch5_movie (비동기 처리 시나리오) =====
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS seed_ch5_movies()
BEGIN
  DECLARE i INT DEFAULT 1;
  WHILE i <= 100 DO
    INSERT INTO ch5_movie (title) VALUES (CONCAT('Movie ', i));
    SET i = i + 1;
  END WHILE;
END //
DELIMITER ;
CALL seed_ch5_movies();
DROP PROCEDURE IF EXISTS seed_ch5_movies;
