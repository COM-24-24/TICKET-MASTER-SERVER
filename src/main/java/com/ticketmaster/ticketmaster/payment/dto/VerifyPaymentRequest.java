package com.ticketmaster.ticketmaster.payment.dto;

import jakarta.validation.constraints.NotNull;

public record VerifyPaymentRequest(@NotNull Long orderId) {
}
