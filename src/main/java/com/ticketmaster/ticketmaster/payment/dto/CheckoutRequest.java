package com.ticketmaster.ticketmaster.payment.dto;

import jakarta.validation.constraints.NotNull;

public record CheckoutRequest(@NotNull Long orderId) {
}
