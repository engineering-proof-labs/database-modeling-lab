package com.engineeringprooflabs.datamodeling.orders;

import java.time.Instant;
import java.util.UUID;

public record CustomerOrderCursor(
        String backend,
        String customerId,
        int limit,
        Instant createdAt,
        UUID id) {
}
