package com.engineeringprooflabs.datamodeling.orders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderStore {

    String backend();

    boolean customerExists(String customerId);

    void createOrder(OrderView order);

    Optional<OrderView> findById(String id);

    int confirmPending(String id, Instant confirmedAt);

    boolean orderExists(String id);

    List<OrderSummary> listCustomerOrders(String customerId, CustomerOrderCursor cursor, int fetchLimit);

    List<OrderSummary> listPendingOrders(PendingOrderWindow window, int fetchLimit);
}
