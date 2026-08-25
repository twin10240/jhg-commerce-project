package com.jhg.hgpage.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** S4 보상 스윕(BackorderSweeper)용 스케줄링 활성화. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "spring.task.scheduling.enabled", matchIfMissing = true)
public class SchedulingConfig {
}
