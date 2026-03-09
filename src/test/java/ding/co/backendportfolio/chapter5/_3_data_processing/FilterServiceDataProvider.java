package ding.co.backendportfolio.chapter5._3_data_processing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class FilterServiceDataProvider {

    public static final int LIST_SIZE = 100;
    public static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz";
    public static final int ALPHABET_LENGTH = ALPHABET.length();

    // 100개의 게시글 데이터를 간편하게 만들어주는 테스트 fixture 용 클래스
    // 100개의 게시글 객체(empty)를 생성해서 리스트에 넣어주고, 안에서 게시글 객체에 필드들 설정. 이후 100개의 post 객체 담은 list 반환
    public static List<Post> createTestData() {
        List<Integer> list = new ArrayList<>();
        for (int i = 1; i <= LIST_SIZE; i++) {
            list.add(i);
        }

        return list.stream()
                .map(intValue ->
                        Post.builder()
                                .id(Long.valueOf(intValue))
                                .content(generateRandomString())
                                .build()
                )
                .toList();
    }

    /**
     * - 알파벳 소문자로 구성된 길이가 10인 랜덤한 문자열 생성
     */
    private static String generateRandomString() {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        StringBuilder stringBuilder = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            int index = random.nextInt(ALPHABET_LENGTH);
            stringBuilder.append(ALPHABET.charAt(index));
        }

        return stringBuilder.toString();
    }
}
