
package com.ticketmaster.ticketmaster.ticket;

import com.ticketmaster.ticketmaster.events.entity.Event;
import com.ticketmaster.ticketmaster.events.entity.EventRepository;
import com.ticketmaster.ticketmaster.events.entity.TicketType;
import com.ticketmaster.ticketmaster.events.entity.TicketTypeRepository;
import com.ticketmaster.ticketmaster.order.entity.Order;
import com.ticketmaster.ticketmaster.order.entity.OrderItem;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TicketIssuingService {

    private final TicketRepository ticketRepository;
    private final EventRepository eventRepository;
    private final TicketTypeRepository ticketTypeRepository;

    public TicketIssuingService(
            TicketRepository ticketRepository,
            EventRepository eventRepository,
            TicketTypeRepository ticketTypeRepository) {

        this.ticketRepository = ticketRepository;
        this.eventRepository = eventRepository;
        this.ticketTypeRepository = ticketTypeRepository;
    }

    @Transactional
    public List<Ticket> issueTickets(Order order) {

        if (order == null
                || order.getId() == null
                || order.getUser() == null
                || order.getUser().getId() == null
                || order.getEventId() == null) {
            throw new IllegalArgumentException(
                    "A valid order, customer, and event are required."
            );
        }

        if (order.getStatus() != Order.OrderStatus.PAID) {
            throw new IllegalStateException(
                    "Tickets can only be issued for PAID orders."
            );
        }

        if (order.getItems() == null || order.getItems().isEmpty()) {
            throw new IllegalArgumentException("The order has no items.");
        }

        if (ticketRepository.existsByOrderId(order.getId())) {
            return ticketRepository.findAllByOrderId(order.getId());
        }

        Event event = eventRepository.findById(order.getEventId())
                .orElseThrow(() ->
                        new IllegalArgumentException("Event not found."));

        List<Ticket> tickets = new ArrayList<>();
        LocalDateTime issuedAt = LocalDateTime.now();

        for (OrderItem item : order.getItems()) {

            if (item.getTicketTypeId() == null
                    || item.getQuantity() == null
                    || item.getQuantity() <= 0) {
                throw new IllegalArgumentException(
                        "Every order item must have a valid ticket type and positive quantity."
                );
            }

            TicketType ticketType = ticketTypeRepository
                    .findByIdForUpdate(item.getTicketTypeId())
                    .orElseThrow(() ->
                            new IllegalArgumentException(
                                    "Ticket type not found: "
                                            + item.getTicketTypeId()
                            ));

            if (ticketType.getEvent() == null
                    || !ticketType.getEvent().getId()
                            .equals(order.getEventId())) {
                throw new IllegalArgumentException(
                        "Ticket type does not belong to the event in this order."
                );
            }

            for (int i = 0; i < item.getQuantity(); i++) {

                Ticket ticket = new Ticket();
                ticket.setOrderId(order.getId());
                ticket.setOwnerId(order.getUser().getId());
                ticket.setEvent(event);
                ticket.setTicketType(ticketType);
                ticket.setTicketCode(UUID.randomUUID().toString());
                ticket.setStatus(TicketStatus.VALID);
                ticket.setIssuedAt(issuedAt);

                tickets.add(ticket);
            }
        }

        return ticketRepository.saveAll(tickets);
    }
}
