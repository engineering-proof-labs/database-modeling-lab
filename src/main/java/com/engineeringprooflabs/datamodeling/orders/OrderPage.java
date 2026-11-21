package com.engineeringprooflabs.datamodeling.orders;

import java.util.List;

public record OrderPage(List<OrderSummary> items, String nextCursor) {
    public OrderPage {
        items = List.copyOf(items);
    }
}
