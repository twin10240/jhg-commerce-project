package com.jhg.hgpage.service;

/**
 * TimeTraceAop 테스트용 픽스처 — service 패키지에 위치해 포인트컷의 "대상"이 된다.
 * (스테레오타입 애너테이션이 없어 컴포넌트 스캔에는 잡히지 않는다.)
 */
public class TimeTraceTracedFixture {
    public String work() {
        return "done";
    }
}
