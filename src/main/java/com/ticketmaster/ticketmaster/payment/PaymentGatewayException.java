package com.ticketmaster.ticketmaster.payment;

import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

public class PaymentGatewayException extends RuntimeException {

    private final String operation;
    private final FailureType failureType;

    public PaymentGatewayException() {
        this("request", FailureType.INVALID_RESPONSE, null);
    }

    public PaymentGatewayException(Throwable cause) {
        this("request", classify(cause), cause);
    }

    public PaymentGatewayException(String operation, Throwable cause) {
        this(operation, classify(cause), cause);
    }

    public PaymentGatewayException(String operation, FailureType failureType) {
        this(operation, failureType, null);
    }

    public PaymentGatewayException(String operation, FailureType failureType, Throwable cause) {
        super("Payment gateway request failed.", cause);
        this.operation = operation;
        this.failureType = failureType;
    }

    public String getOperation() {
        return operation;
    }

    public FailureType getFailureType() {
        return failureType;
    }

    public Integer getProviderStatusCode() {
        return getCause() instanceof HttpStatusCodeException exception
                ? exception.getStatusCode().value()
                : null;
    }

    public boolean isDefinitiveRejection() {
        return failureType == FailureType.CONFIGURATION
                || getCause() instanceof IllegalArgumentException
                || (getCause() instanceof HttpStatusCodeException exception
                        && exception.getStatusCode().is4xxClientError());
    }

    private static FailureType classify(Throwable cause) {
        if (cause instanceof HttpStatusCodeException) {
            return FailureType.PROVIDER_RESPONSE;
        }
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException) {
                return FailureType.TIMEOUT;
            }
        }
        if (cause instanceof ResourceAccessException) {
            return FailureType.TRANSPORT;
        }
        return FailureType.INVALID_RESPONSE;
    }

    public enum FailureType {
        CONFIGURATION,
        TIMEOUT,
        PROVIDER_RESPONSE,
        TRANSPORT,
        INVALID_RESPONSE
    }
}
