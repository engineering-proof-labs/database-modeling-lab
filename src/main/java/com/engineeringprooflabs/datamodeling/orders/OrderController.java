package com.engineeringprooflabs.datamodeling.orders;

import java.net.URI;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final boolean failureInjectionEnabled;

    public OrderController(
            OrderService orderService,
            @Value("${lab.failure-injection-enabled:false}") boolean failureInjectionEnabled) {
        this.orderService = orderService;
        this.failureInjectionEnabled = failureInjectionEnabled;
    }

    @PostMapping
    ResponseEntity<OrderView> create(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(name = "X-Lab-Fail-After-Order", defaultValue = "false") boolean failAfterOrder) {
        if (failAfterOrder && !failureInjectionEnabled) {
            throw new FailureInjectionDisabledException();
        }

        OrderView order = orderService.create(request, failAfterOrder);
        return ResponseEntity.created(URI.create("/orders/" + order.id())).body(order);
    }

    @GetMapping("/{id}")
    OrderView get(@PathVariable String id) {
        return orderService.findById(id);
    }

    @PostMapping("/{id}/confirm")
    OrderView confirm(@PathVariable String id) {
        return orderService.confirm(id);
    }
}
