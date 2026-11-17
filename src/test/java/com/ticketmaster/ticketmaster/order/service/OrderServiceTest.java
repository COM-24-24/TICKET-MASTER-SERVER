package com.ticketmaster.ticketmaster.order.service;

import com.ticketmaster.ticketmaster.events.entity.EventRepository;
import com.ticketmaster.ticketmaster.events.entity.TicketTypeRepository;
import com.ticketmaster.ticketmaster.order.dto.OrderResponse;
import com.ticketmaster.ticketmaster.order.dto.PlaceOrderRequest;
import com.ticketmaster.ticketmaster.order.entity.Order;
import com.ticketmaster.ticketmaster.order.repository.OrderRepository;
import com.ticketmaster.ticketmaster.ticket.TicketIssuingService;
import com.ticketmaster.ticketmaster.common.exception.NotFoundException;
import com.ticketmaster.ticketmaster.order.service.OrderService;
import com.ticketmaster.ticketmaster.user.entity.User;
import com.ticketmaster.ticketmaster.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private UserRepository userRepository;
    @Mock private EventRepository eventRepository;
    @Mock private TicketTypeRepository ticketTypeRepository;
    @Mock private TicketIssuingService ticketIssuingService;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, userRepository, eventRepository,
                ticketTypeRepository, ticketIssuingService, 10, 30);
    }

    // Helper to mock the logged-in user
    private void mockCurrentUser(String email) {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(email);
        SecurityContext context = mock(SecurityContext.class);
        when(context.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(context);
    }

    @Test
    void placeOrder_shouldThrowIfEventNotFound() {
        PlaceOrderRequest request = new PlaceOrderRequest();
        request.setEventId(1L);
        request.setItems(Collections.singletonList(new PlaceOrderRequest.ItemRequest(1L, 2)));

        assertThrows(NotFoundException.class, () -> orderService.placeOrder(request));
    }

    @Test
    void markOrderPaid_shouldBeIdempotent() {
        Order order = Order.builder().id(1L).status(Order.OrderStatus.PAID).build();
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        orderService.markOrderPaid(1L, "test");

        verify(orderRepository, never()).save(any(Order.class));
        verifyNoInteractions(ticketIssuingService);
    }

    @Test
    void cancelOrder_shouldThrowIfNotOwner() {
        mockCurrentUser("wrong@test.com");
        User owner = User.builder().email("owner@test.com").build();
        Order order = Order.builder().id(1L).user(owner).status(Order.OrderStatus.PENDING).build();
        
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        assertThrows(NotFoundException.class,
                () -> orderService.cancelOrder(1L));
    }
}
