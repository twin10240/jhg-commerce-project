package com.jhg.hgpage.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class TimeTraceAop {
    // 핵심 계층(service/controller/api)만 측정한다. domain getter·DTO·repository까지
    // 감싸면 로그가 폭증하고 성능이 저하되므로 비즈니스 호출로 범위를 한정한다.
    @Around("execution(* com.jhg.hgpage.service..*(..)) || " +
            "execution(* com.jhg.hgpage.controller..*(..)) || " +
            "execution(* com.jhg.hgpage.api..*(..))")
    public Object execute(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        log.info("START: {}", joinPoint.getSignature());

        try {
            return joinPoint.proceed();
        } finally {
            long timeMs = System.currentTimeMillis() - start;
            log.info("END: {} {}ms", joinPoint.getSignature(), timeMs);
        }
    }
}
