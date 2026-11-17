package com.ticketmaster.ticketmaster.ticket;

import com.ticketmaster.ticketmaster.ticket.dto.TicketResponse;
import com.ticketmaster.ticketmaster.common.exception.NotFoundException;
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
    void getMyTicketByIdReturnsNotFoundWhenTicketIsNotOwnedByCustomer() {
        when(ticketRepository.findByIdAndOwnerId(10L, 7L))
                .thenReturn(Optional.empty());
        when(ticketRepository.existsById(10L)).thenReturn(true);

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
}
