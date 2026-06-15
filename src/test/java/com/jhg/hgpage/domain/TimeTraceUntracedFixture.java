package com.jhg.hgpage.domain;

/**
 * TimeTraceAop 테스트용 픽스처 — domain 패키지에 위치해 포인트컷의 "비대상"이 되어야 한다.
 * (스테레오타입 애너테이션이 없어 컴포넌트 스캔에는 잡히지 않는다.)
 */
public class TimeTraceUntracedFixture {
    public String work() {
        return "done";
    }
}
