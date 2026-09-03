package com.jhg.hgpage.realtime.outbox;

import java.util.Objects;

public record DeliveryResult(Outcome outcome, String errorCode) {

    public enum Outcome { SUCCESS, RETRYABLE_FAILURE, PERMANENT_FAILURE }

    public static DeliveryResult success() {
        return new DeliveryResult(Outcome.SUCCESS, null);
    }

    public static DeliveryResult retryable(String errorCode) {
        return new DeliveryResult(Outcome.RETRYABLE_FAILURE, Objects.requireNonNull(errorCode));
    }

    public static DeliveryResult permanent(String errorCode) {
        return new DeliveryResult(Outcome.PERMANENT_FAILURE, Objects.requireNonNull(errorCode));
    }
}
