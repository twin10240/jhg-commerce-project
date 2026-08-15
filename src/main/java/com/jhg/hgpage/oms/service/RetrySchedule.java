package com.jhg.hgpage.oms.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class RetrySchedule {

    private static final List<Duration> DELAYS = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2));

    public Optional<LocalDateTime> nextAttemptAt(int completedAttempts, LocalDateTime now) {
        if (completedAttempts < 1 || completedAttempts > DELAYS.size()) {
            return Optional.empty();
        }
        return Optional.of(now.plus(DELAYS.get(completedAttempts - 1)));
    }
}
