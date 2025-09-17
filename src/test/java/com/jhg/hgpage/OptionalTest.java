package com.jhg.hgpage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

public class OptionalTest {

    @Test
    public void test01() {
        Optional<String> emptyOpt = Optional.empty();         // 비어있는 Optional
        Optional<String> nameOpt = Optional.of("그니");       // 값이 무조건 있는 경우
        Optional<String> nullOpt = Optional.ofNullable(null); // null 허용

        System.err.println(emptyOpt);
        System.err.println(nameOpt.get());
        System.err.println(nullOpt);

        String safeValue = nameOpt.orElse("기본값");
        System.out.println(safeValue); // "그니"

        String safeValue2 = emptyOpt.orElse("기본값");
        System.out.println(safeValue2); // "그니"

        if (nameOpt.isPresent()) {
            System.out.println("값 있음!");
        }

        nameOpt.ifPresent(System.out::println); // 값이 있으면 출력

//        String name = Optional.ofNullable(nullOpt)
//                .map(MyObj::getName)
//                .orElse("기본값");
    }
}
