package com.jhg.hgpage.aop;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jhg.hgpage.domain.TimeTraceUntracedFixture;
import com.jhg.hgpage.service.TimeTraceTracedFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TimeTraceAop가 (1) 측정 결과를 SLF4J 로거로 남기고,
 * (2) 포인트컷이 service/controller/api 계층으로 한정되어 domain 계층은 트레이스하지 않는지 검증한다.
 * AspectJProxyFactory로 어드바이스를 적용하고 logback ListAppender로 로그를 캡처한다.
 */
class TimeTraceAopTest {

    private Logger aopLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        aopLogger = (Logger) LoggerFactory.getLogger(TimeTraceAop.class);
        aopLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        aopLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        aopLogger.detachAppender(appender);
    }

    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new TimeTraceAop());
        return factory.getProxy();
    }

    private List<String> capturedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void 서비스_계층_호출은_타이밍_로그를_로거로_남긴다() {
        TimeTraceTracedFixture target = proxy(new TimeTraceTracedFixture());

        target.work();

        List<String> messages = capturedMessages();
        assertThat(messages).anyMatch(m -> m.startsWith("START") && m.contains("work"));
        assertThat(messages).anyMatch(m -> m.startsWith("END") && m.contains("work") && m.contains("ms"));
    }

    @Test
    void 도메인_계층_호출은_트레이스되지_않는다() {
        TimeTraceUntracedFixture target = proxy(new TimeTraceUntracedFixture());

        target.work();

        assertThat(capturedMessages()).isEmpty();
    }
}
