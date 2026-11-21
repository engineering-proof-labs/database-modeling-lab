package com.engineeringprooflabs.datamodeling.orders;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!oracle-ebr")
public class OrderService {

    private final OrderStore repository;
    private final ObjectProvider<CreateFailureProbe> failureProbe;
    private final CustomerOrderCursorCodec cursorCodec;
    private final Clock clock;

    public OrderService(
            OrderStore repository,
            ObjectProvider<CreateFailureProbe> failureProbe,
            CustomerOrderCursorCodec cursorCodec,
            Clock clock) {
        this.repository = repository;
        this.failureProbe = failureProbe;
        this.cursorCodec = cursorCodec;
        this.clock = clock;
    }

    public OrderView create(CreateOrderRequest request, boolean failAfterOrder) {
        if (!repository.customerExists(request.customerId())) {
            throw new CustomerNotFoundException(request.customerId());
        }

        String id = UUID.randomUUID().toString();
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        List<OrderItemView> items = new ArrayList<>(request.items().size());
        long totalCents = 0;
        int itemNumber = 1;
        for (CreateOrderItemRequest item : request.items()) {
            long lineTotal = Math.multiplyExact(item.unitPriceCents(), item.quantity());
            totalCents = Math.addExact(totalCents, lineTotal);
            items.add(new OrderItemView(itemNumber++, item.productName(), item.quantity(),
                    item.unitPriceCents(), lineTotal));
        }

        OrderView order = new OrderView(id, request.customerId(), "PENDING", totalCents,
                createdAt, null, List.copyOf(items));
        if (failAfterOrder) {
            CreateFailureProbe probe = failureProbe.getIfAvailable();
            if (probe == null) {
                throw new FailureInjectionDisabledException();
            }
            probe.insertHeaderThenFail(order);
        } else {
            repository.createOrder(order);
        }
        return order;
    }

    public OrderView findById(String id) {
        return repository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    public OrderView confirm(String id) {
        int changedRows = repository.confirmPending(id, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        if (changedRows == 1) {
            return findById(id);
        }
        if (!repository.orderExists(id)) {
            throw new OrderNotFoundException(id);
        }
        throw new OrderAlreadyConfirmedException(id);
    }

    public OrderPage listCustomerOrders(String customerId, int limit, String encodedCursor) {
        if (limit < 1 || limit > 50) {
            throw new InvalidPageSizeException();
        }
        if (!repository.customerExists(customerId)) {
            throw new CustomerNotFoundException(customerId);
        }

        CustomerOrderCursor cursor = encodedCursor == null ? null : cursorCodec.decode(encodedCursor);
        if (cursor != null && (!repository.backend().equals(cursor.backend())
                || !customerId.equals(cursor.customerId()) || limit != cursor.limit())) {
            throw new InvalidCursorException();
        }

        List<OrderSummary> fetched = repository.listCustomerOrders(customerId, cursor, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<OrderSummary> pageItems = List.copyOf(fetched.subList(0, Math.min(limit, fetched.size())));
        String nextCursor = hasMore
                ? cursorCodec.encode(repository.backend(), customerId, limit, pageItems.getLast())
                : null;
        return new OrderPage(pageItems, nextCursor);
    }

    public OrderPage listPendingOrders(int limit, String encodedCursor) {
        if (limit < 1 || limit > 50) {
            throw new InvalidPageSizeException();
        }
        PendingOrderWindow window = encodedCursor == null
                ? new PendingOrderWindow(clock.instant().truncatedTo(ChronoUnit.MILLIS), null)
                : PendingOrderCursorCodec.decode(encodedCursor, repository.backend(), limit);
        List<OrderSummary> fetched = repository.listPendingOrders(window, limit + 1);
        List<OrderSummary> pageItems = List.copyOf(fetched.subList(0, Math.min(limit, fetched.size())));
        String next = fetched.size() > limit
                ? PendingOrderCursorCodec.encode(repository.backend(), limit, window.asOf(), pageItems.getLast())
                : null;
        return new OrderPage(pageItems, next);
    }
}
