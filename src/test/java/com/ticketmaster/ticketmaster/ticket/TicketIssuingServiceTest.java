package com.ticketmaster.ticketmaster.ticket;

import com.ticketmaster.ticketmaster.events.entity.Event;
import com.ticketmaster.ticketmaster.events.entity.EventRepository;
import com.ticketmaster.ticketmaster.events.entity.TicketType;
import com.ticketmaster.ticketmaster.events.entity.TicketTypeRepository;
import com.ticketmaster.ticketmaster.order.entity.Order;
import com.ticketmaster.ticketmaster.order.entity.OrderItem;
import com.ticketmaster.ticketmaster.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketIssuingServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private TicketTypeRepository ticketTypeRepository;

    @InjectMocks
    private TicketIssuingService ticketIssuingService;

    private Order createPaidOrder(int quantity) {
        User customer = new User();
        customer.setId(20L);

        OrderItem item = new OrderItem();
        item.setTicketTypeId(40L);
        item.setQuantity(quantity);

        Order order = new Order();
        order.setId(10L);
        order.setUser(customer);
        order.setEventId(30L);
        order.setStatus(Order.OrderStatus.PAID);
        order.setItems(new ArrayList<>(List.of(item)));

        item.setOrder(order);

        return order;
    }

    private void mockEventAndTicketType() {
        Event event = mock(Event.class);
        TicketType ticketType = mock(TicketType.class);

        when(event.getId()).thenReturn(30L);
        when(ticketType.getEvent()).thenReturn(event);

        when(eventRepository.findById(30L))
                .thenReturn(Optional.of(event));

        when(ticketTypeRepository.findByIdForUpdate(40L))
                .thenReturn(Optional.of(ticketType));
    }

    @Test
    void issuesOneTicketForEachPurchasedUnit() {
        Order order = createPaidOrder(3);
        mockEventAndTicketType();

        when(ticketRepository.existsByOrderId(10L)).thenReturn(false);

        when(ticketRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<Ticket> result = ticketIssuingService.issueTickets(order);

        assertEquals(3, result.size());

        assertTrue(result.stream().allMatch(
                ticket -> ticket.getStatus() == TicketStatus.VALID));

        assertTrue(result.stream().allMatch(
                ticket -> ticket.getOrderId().equals(10L)));

        assertTrue(result.stream().allMatch(
                ticket -> ticket.getOwnerId().equals(20L)));

        assertTrue(result.stream().allMatch(
                ticket -> ticket.getEvent() != null));

        assertTrue(result.stream().allMatch(
                ticket -> ticket.getTicketType() != null));

        assertTrue(result.stream().allMatch(
                ticket -> ticket.getIssuedAt() != null));

        assertTrue(result.stream().allMatch(ticket -> {
            try {
                UUID.fromString(ticket.getTicketCode());
                return true;
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }));

        assertEquals(3, result.stream()
                .map(Ticket::getTicketCode)
                .distinct()
                .count());

        verify(ticketRepository).saveAll(anyList());
    }

    @Test
    void doesNotIssueDuplicateTicketsForAnAlreadyProcessedOrder() {
        Order order = createPaidOrder(3);

        Ticket existingTicket = mock(Ticket.class);

        when(ticketRepository.existsByOrderId(10L)).thenReturn(true);
        when(ticketRepository.findAllByOrderId(10L))
                .thenReturn(List.of(existingTicket));

        List<Ticket> result = ticketIssuingService.issueTickets(order);

        assertEquals(1, result.size());
        assertSame(existingTicket, result.get(0));

        verify(ticketRepository, never()).saveAll(anyList());
        verifyNoInteractions(eventRepository, ticketTypeRepository);
    }

    @Test
    void rejectsAnUnpaidOrder() {
        Order order = createPaidOrder(1);
        order.setStatus(Order.OrderStatus.PENDING);

        assertThrows(IllegalStateException.class,
                () -> ticketIssuingService.issueTickets(order));

        verifyNoInteractions(ticketRepository);
        verifyNoInteractions(eventRepository, ticketTypeRepository);
    }

    @Test
    void rejectsZeroOrNegativeQuantities() {
        Order order = createPaidOrder(0);

        when(ticketRepository.existsByOrderId(10L)).thenReturn(false);

        assertThrows(IllegalArgumentException.class,
                () -> ticketIssuingService.issueTickets(order));

        verify(ticketRepository, never()).saveAll(anyList());
        verify(ticketTypeRepository, never()).findByIdForUpdate(anyLong());
    }
}
