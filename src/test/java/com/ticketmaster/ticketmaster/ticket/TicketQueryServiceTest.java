package com.ticketmaster.ticketmaster.ticket;

import com.ticketmaster.ticketmaster.ticket.dto.TicketResponse;
import com.ticketmaster.ticketmaster.common.exception.NotFoundException;
import com.ticketmaster.ticketmaster.customer.CustomerProfileRepository;
import com.ticketmaster.ticketmaster.entity.Payment;
import com.ticketmaster.ticketmaster.entity.PaymentStatus;
import com.ticketmaster.ticketmaster.order.entity.Order;
import com.ticketmaster.ticketmaster.order.repository.OrderRepository;
import com.ticketmaster.ticketmaster.repository.PaymentRepository;
import com.ticketmaster.ticketmaster.user.entity.User;
import com.ticketmaster.ticketmaster.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketQueryServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CustomerProfileRepository customerProfileRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @InjectMocks
    private TicketQueryService ticketQueryService;

    @Test
    void getMyTicketsUsesTheRequestedOwnerId() {
        when(ticketRepository.findByOwnerIdOrderByIssuedAtDesc(7L))
                .thenReturn(List.of());

        List<TicketResponse> result = ticketQueryService.getMyTickets(7L);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(ticketRepository).findByOwnerIdOrderByIssuedAtDesc(7L);
    }

    @Test
    void getMyTicketsExcludesUnpaidFailedAndCancelledTickets() {
        Ticket unpaid = ticket(7L, 10L, TicketStatus.VALID);
        Ticket failedPayment = ticket(7L, 11L, TicketStatus.VALID);
        Ticket cancelled = ticket(7L, 12L, TicketStatus.CANCELLED);
        Ticket refundedPayment = ticket(7L, 13L, TicketStatus.VALID);
        when(ticketRepository.findByOwnerIdOrderByIssuedAtDesc(7L))
                .thenReturn(List.of(unpaid, failedPayment, cancelled, refundedPayment));
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order(
                10L, 7L, Order.OrderStatus.PENDING)));
        when(orderRepository.findById(11L)).thenReturn(Optional.of(order(
                11L, 7L, Order.OrderStatus.PAID)));
        when(orderRepository.findById(13L)).thenReturn(Optional.of(order(
                13L, 7L, Order.OrderStatus.PAID)));
        Payment payment = new Payment();
        payment.setStatus(PaymentStatus.FAILED);
        when(paymentRepository.findByOrderIdOrderByCreatedAtDesc(11L))
                .thenReturn(List.of(payment));
        Payment refund = new Payment();
        refund.setStatus(PaymentStatus.REFUNDED);
        when(paymentRepository.findByOrderIdOrderByCreatedAtDesc(13L))
                .thenReturn(List.of(refund));

        assertTrue(ticketQueryService.getMyTickets(7L).isEmpty());
        verifyNoInteractions(userRepository, customerProfileRepository);
    }

    @Test
    void getMyTicketByIdReturnsNotFoundWhenTicketIsNotOwnedByCustomer() {
        when(ticketRepository.findByIdAndOwnerId(10L, 7L))
                .thenReturn(Optional.empty());
        NotFoundException exception = assertThrows(
                NotFoundException.class,
                () -> ticketQueryService.getMyTicketById(10L, 7L)
        );

        assertEquals("Ticket not found.", exception.getMessage());
        verify(ticketRepository).findByIdAndOwnerId(10L, 7L);
    }

    @Test
    void getMyTicketByIdReturnsNotFoundWhenTicketDoesNotExist() {
        when(ticketRepository.findByIdAndOwnerId(10L, 7L))
                .thenReturn(Optional.empty());

        assertThrows(
                NotFoundException.class,
                () -> ticketQueryService.getMyTicketById(10L, 7L)
        );

        verify(ticketRepository, never()).existsById(10L);
        verify(ticketRepository, never()).findById(10L);
    }

    private Ticket ticket(Long ownerId, Long orderId, TicketStatus status) {
        Ticket ticket = new Ticket();
        ticket.setOwnerId(ownerId);
        ticket.setOrderId(orderId);
        ticket.setStatus(status);
        return ticket;
    }

    private Order order(Long orderId, Long ownerId, Order.OrderStatus status) {
        return Order.builder()
                .id(orderId)
                .user(User.builder().id(ownerId).build())
                .status(status)
                .build();
    }
}
