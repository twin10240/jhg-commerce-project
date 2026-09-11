package com.jhg.hgpage.realtime.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeAvailabilityWarningTest {

    @Test
    void 실시간_기능이_모두_꺼져_있으면_비활성_경고를_남긴다() {
        Logger logger = (Logger) LoggerFactory.getLogger(RealtimeAvailabilityWarning.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        new RealtimeAvailabilityWarning(false, false, "");

        assertThat(appender.list).anyMatch(event ->
                event.getFormattedMessage().contains("실시간 알림 비활성"));
        logger.detachAppender(appender);
    }
}
