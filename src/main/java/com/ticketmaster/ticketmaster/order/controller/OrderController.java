package com.ticketmaster.ticketmaster.order.controller;

import com.ticketmaster.ticketmaster.order.dto.OrderResponse;
import com.ticketmaster.ticketmaster.order.dto.PlaceOrderRequest;
import com.ticketmaster.ticketmaster.order.service.OrderService;
import com.ticketmaster.ticketmaster.ticket.TicketQueryService;
import com.ticketmaster.ticketmaster.ticket.dto.TicketResponse;
import com.ticketmaster.ticketmaster.user.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Place and view orders")
public class OrderController {

    private final OrderService orderService;
    private final TicketQueryService ticketQueryService;
    private final UserRepository userRepository;

    public OrderController(
            OrderService orderService,
            TicketQueryService ticketQueryService,
            UserRepository userRepository) {
        this.orderService = orderService;
        this.ticketQueryService = ticketQueryService;
        this.userRepository = userRepository;
    }

    @PostMapping
    @Operation(summary = "Place a new order")
    public ResponseEntity<OrderResponse> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        return ResponseEntity.ok(orderService.placeOrder(request));
    }

    @GetMapping("/my")
    @Operation(summary = "Get my order history")
    public ResponseEntity<List<OrderResponse>> getMyOrders() {
        return ResponseEntity.ok(orderService.getMyOrders());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get order details by ID")
    public ResponseEntity<OrderResponse> getOrderById(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrderById(id));
    }

    @GetMapping("/{id}/tickets")
    @Operation(summary = "Get tickets issued for an owned order")
    public ResponseEntity<List<TicketResponse>> getOrderTickets(
            @PathVariable Long id, Authentication authentication) {
        orderService.getOrderById(id);
        Long ownerId = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user was not found in the database."))
                .getId();
        return ResponseEntity.ok(ticketQueryService.getOrderTickets(id, ownerId));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a PENDING order")
    public ResponseEntity<OrderResponse> cancelOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.cancelOrder(id));
    }

}