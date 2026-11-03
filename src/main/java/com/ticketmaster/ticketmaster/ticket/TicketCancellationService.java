package com.ticketmaster.ticketmaster.ticket;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TicketCancellationService {

    private final TicketRepository ticketRepository;

    public TicketCancellationService(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Transactional
    public void cancelMyTicket(Long ticketId, Long ownerId) {

        Ticket ticket = ticketRepository
                .findByIdAndOwnerIdForUpdate(ticketId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Ticket not found or you do not have permission to cancel it."
                ));

        if (ticket.getStatus() == TicketStatus.USED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A ticket that has already been used cannot be cancelled."
            );
        }

        if (ticket.getStatus() == TicketStatus.CANCELLED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This ticket has already been cancelled."
            );
        }

        if (ticket.getStatus() != TicketStatus.VALID) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This ticket cannot be cancelled."
            );
        }

        ticket.setStatus(TicketStatus.CANCELLED);
    }
}