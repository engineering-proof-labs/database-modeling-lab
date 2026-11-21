package com.engineeringprooflabs.datamodeling.orders;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public record PendingOrderWindow(Instant asOf, Position after) {

    Instant start() {
        return asOf.minus(30, ChronoUnit.MINUTES);
    }

    record Position(Instant createdAt, UUID id) {
    }
}
