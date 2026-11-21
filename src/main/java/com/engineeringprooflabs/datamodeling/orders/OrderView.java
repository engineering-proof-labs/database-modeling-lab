package com.engineeringprooflabs.datamodeling.orders;

import java.time.Instant;
import java.util.List;

public record OrderView(
        String id,
        String customerId,
        String status,
        long totalCents,
        Instant createdAt,
        Instant confirmedAt,
        List<OrderItemView> items) {
}
