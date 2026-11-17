package com.ticketmaster.ticketmaster.payment.dto;

import com.ticketmaster.ticketmaster.order.dto.OrderResponse;
import com.ticketmaster.ticketmaster.ticket.dto.TicketResponse;

import java.util.List;

public record PaidOrderResponse(
        OrderResponse order,
        List<TicketResponse> tickets,
        String paymentStatus) {
}
