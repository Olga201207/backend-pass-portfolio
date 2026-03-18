-- SESSION 변수 설정 (SET_VAR 힌트는 SUPER 권한 필요, SET SESSION은 일반 권한으로 가능)
SET SESSION cte_max_recursion_depth = 1000000;
SET SESSION net_read_timeout = 3600;
SET SESSION net_write_timeout = 3600;

-- 테이블 초기화 (멱등성: 재실행해도 안전)
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE ch2_board_likes;
TRUNCATE TABLE ch2_board_tags;
TRUNCATE TABLE ch2_boards;
TRUNCATE TABLE ch2_members;
TRUNCATE TABLE ch2_tags;
SET FOREIGN_KEY_CHECKS = 1;

-- 100만 명의 회원 생성
INSERT INTO ch2_members (email, password, nickname, created_at, updated_at)
WITH RECURSIVE numbers AS (
    SELECT 1 as n
    UNION ALL
    SELECT n + 1 FROM numbers WHERE n < 1000000
)
SELECT
    CONCAT('user', LPAD(n, 7, '0'), '@test.com'),
    '$2a$10$iWPQvWHXRaJvPpZn1qzp3.GKBHXa9mFQXj4.Nz.yu.YyXJxvxXnwi',
    CONCAT('User', LPAD(n, 7, '0')),
    DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 365) DAY),
    NOW()
FROM numbers;

-- 50만 개의 게시글 생성
INSERT INTO ch2_boards (title, content, category, view_count, member_id, created_at, updated_at)
WITH RECURSIVE numbers AS (
    SELECT 1 as n
    UNION ALL
    SELECT n + 1 FROM numbers WHERE n < 500000
)
SELECT
    CONCAT('Title ', n),
    CONCAT('Content ', n, ' ', REPEAT('Lorem ipsum dolor sit amet. ', 10)),
    CASE MOD(n, 4)
        WHEN 0 THEN 'NOTICE'
        WHEN 1 THEN 'FREE'
        WHEN 2 THEN 'QUESTION'
        WHEN 3 THEN 'TECH'
    END,
    FLOOR(RAND() * 100000),
    1 + MOD(n, 1000000),
    DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 365) DAY),
    NOW()
FROM numbers;

-- 10000개의 태그 생성
INSERT INTO ch2_tags (name)
WITH RECURSIVE numbers AS (
    SELECT 1 as n
    UNION ALL
    SELECT n + 1 FROM numbers WHERE n < 10000
)
SELECT CONCAT('tag', n)
FROM numbers;

-- 100000개의 게시글-태그 연결
INSERT INTO ch2_board_tags (board_id, tag_id)
WITH RECURSIVE numbers AS (
    SELECT 1 as n
    UNION ALL
    SELECT n + 1 FROM numbers WHERE n < 200000
)
SELECT DISTINCT
    1 + MOD(FLOOR(n/4), 500000) as board_id,  -- 평균적으로 각 게시글당 4개의 태그
    1 + MOD(FLOOR(RAND() * 10000), 10000) as tag_id
FROM numbers
GROUP BY board_id, tag_id;

-- 100000개의 좋아요
INSERT INTO ch2_board_likes (member_id, board_id)
WITH RECURSIVE numbers AS (
    SELECT 1 as n
    UNION ALL
    SELECT n + 1 FROM numbers WHERE n < 200000
)
SELECT DISTINCT
    1 + MOD(FLOOR(RAND() * 1000000), 1000000) as member_id,
    1 + MOD(FLOOR(RAND() * 500000), 500000) as board_id
FROM numbers
GROUP BY member_id, board_id
LIMIT 100000;
