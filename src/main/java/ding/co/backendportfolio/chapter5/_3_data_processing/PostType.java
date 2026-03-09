package ding.co.backendportfolio.chapter5._3_data_processing;

import java.util.concurrent.ThreadLocalRandom;

public enum PostType {
    TEXT,
    IMAGE,
    VIDEO;

    // values(): java가 enum에 자동으로 생성해주는 정적 메서드. 해당 enum의 모든 상수를 선언 순서대로 배열로 반환
    private static final PostType[] VALUES = values();

    public static PostType getByRandom() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int randomInt = random.nextInt(VALUES.length);
        return VALUES[randomInt];
    }
}
