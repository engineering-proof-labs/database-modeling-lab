package com.engineeringprooflabs.datamodeling.orders;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateOrderItemRequest(
        @NotBlank @Size(max = 120) String productName,
        @Min(1) @Max(999) int quantity,
        @Positive @Max(100_000_000) long unitPriceCents) {
}
