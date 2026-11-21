package com.engineeringprooflabs.datamodeling.orders;

import java.time.Instant;

public record OrderSummary(
        String id,
        String customerId,
        String status,
        long totalCents,
        Instant createdAt) {
}
