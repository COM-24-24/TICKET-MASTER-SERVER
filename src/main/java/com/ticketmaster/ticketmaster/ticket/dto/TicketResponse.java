
package com.ticketmaster.ticketmaster.ticket.dto;

import com.ticketmaster.ticketmaster.ticket.Ticket;
import com.ticketmaster.ticketmaster.ticket.TicketStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TicketResponse(
        Long id,
        Long orderId,
        String ticketCode,
        Long eventId,
        String eventTitle,
        String venue,
        String location,
        LocalDateTime eventDateTime,
        LocalDateTime eventStartTime,
        LocalDateTime eventEndTime,
        Long ticketTypeId,
        String ticketTypeName,
        BigDecimal ticketPrice,
        TicketStatus status,
        LocalDateTime issuedAt,
        LocalDateTime usedAt
) {

    public static TicketResponse from(Ticket ticket) {

        return new TicketResponse(
                ticket.getId(),
                ticket.getOrderId(),
                ticket.getTicketCode(),

                ticket.getEvent().getId(),
                ticket.getEvent().getTitle(),
                ticket.getEvent().getVenue(),
                ticket.getEvent().getLocation(),
                ticket.getEvent().getDateTime(),
                ticket.getEvent().getStartTime(),
                ticket.getEvent().getEndTime(),

                ticket.getTicketType().getId(),
                ticket.getTicketType().getName(),
                ticket.getTicketType().getPrice(),

                ticket.getStatus(),
                ticket.getIssuedAt(),
                ticket.getUsedAt()
        );
    }
}
