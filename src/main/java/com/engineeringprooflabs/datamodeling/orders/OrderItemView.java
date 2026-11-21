package com.engineeringprooflabs.datamodeling.orders;

public record OrderItemView(
        int itemNumber,
        String productName,
        int quantity,
        long unitPriceCents,
        long lineTotalCents) {
}
