package com.engineeringprooflabs.datamodeling.orders;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

@RestController
@Profile("!oracle-ebr")
@RequestMapping("/customers")
public class CustomerOrderController {

    private final OrderService orderService;

    public CustomerOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{customerId}/orders")
    OrderPage listOrders(
            @PathVariable String customerId,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String cursor) {
        return orderService.listCustomerOrders(customerId, limit, cursor);
    }
}
