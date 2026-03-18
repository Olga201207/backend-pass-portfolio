-- Chapter 6 Mock Data
-- Book 1000개 시딩: Cache Avalanche 시나리오에서 1000건 동시 만료 테스트 사용
-- 실행: mysql -u portfolio_user -p portfolio < src/main/resources/chapter6/mock-data.sql

DELIMITER //
CREATE PROCEDURE IF NOT EXISTS seed_ch6_books()
BEGIN
  DECLARE i INT DEFAULT 1;
  WHILE i <= 1000 DO
    INSERT INTO ch6_book (name, is_sold_out)
    VALUES (CONCAT('Book ', i), FALSE);
    SET i = i + 1;
  END WHILE;
END //
DELIMITER ;
CALL seed_ch6_books();
DROP PROCEDURE IF EXISTS seed_ch6_books;
