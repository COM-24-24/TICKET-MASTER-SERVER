package com.ticketmaster.ticketmaster.payment;

import org.springframework.web.client.HttpStatusCodeException;

public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException() {
        super("Payment gateway request failed.");
    }

    public PaymentGatewayException(Throwable cause) {
        super("Payment gateway request failed.", cause);
    }

    public boolean isDefinitiveRejection() {
        return getCause() instanceof HttpStatusCodeException exception
                && exception.getStatusCode().is4xxClientError();
    }
}
