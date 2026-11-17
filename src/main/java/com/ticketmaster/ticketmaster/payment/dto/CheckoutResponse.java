package com.ticketmaster.ticketmaster.payment.dto;

import java.math.BigDecimal;

public record CheckoutResponse(
        Long orderId,
        String txRef,
        String checkoutUrl,
        BigDecimal amount,
        String currency,
        String status) {
}
