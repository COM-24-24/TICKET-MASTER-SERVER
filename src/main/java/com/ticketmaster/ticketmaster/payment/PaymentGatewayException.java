package com.ticketmaster.ticketmaster.payment;

public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException() {
        super("Payment gateway request failed.");
    }

    public PaymentGatewayException(Throwable cause) {
        super("Payment gateway request failed.", cause);
    }
}
