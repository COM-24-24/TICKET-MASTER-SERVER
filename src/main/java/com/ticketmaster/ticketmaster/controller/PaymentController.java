package com.ticketmaster.ticketmaster.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketmaster.ticketmaster.common.exception.NotFoundException;
import com.ticketmaster.ticketmaster.payment.PayChanguService;
import com.ticketmaster.ticketmaster.payment.PaymentGatewayException;
import com.ticketmaster.ticketmaster.payment.PaymentProcessingService;
import com.ticketmaster.ticketmaster.payment.dto.CheckoutRequest;
import com.ticketmaster.ticketmaster.payment.dto.CheckoutResponse;
import com.ticketmaster.ticketmaster.payment.dto.PaidOrderResponse;
import com.ticketmaster.ticketmaster.payment.dto.VerifyPaymentRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger logger = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentProcessingService paymentProcessingService;
    private final PayChanguService payChanguService;
    private final ObjectMapper objectMapper;
    private final String appRedirectUri;

    public PaymentController(
            PaymentProcessingService paymentProcessingService,
            PayChanguService payChanguService,
            ObjectMapper objectMapper,
            @Value("${app.payment.app-redirect-uri:myapp://payment-result}") String appRedirectUri) {
        this.paymentProcessingService = paymentProcessingService;
        this.payChanguService = payChanguService;
        this.objectMapper = objectMapper;
        this.appRedirectUri = appRedirectUri;
    }

    @PostMapping("/checkout")
    @Operation(summary = "Create a PayChangu checkout for an owned pending order")
    public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest request) {
        PaymentProcessingService.CheckoutResult result =
                paymentProcessingService.checkout(request.orderId());
        return new CheckoutResponse(
                request.orderId(), result.txRef(), result.checkoutUrl(),
                result.amount(), "MWK", "PENDING");
    }

    @PostMapping("/verify")
    @Operation(summary = "Verify PayChangu payment attempts for an owned order")
    public PaidOrderResponse verify(@Valid @RequestBody VerifyPaymentRequest request) {
        PaymentProcessingService.PaidOrderResult result =
                paymentProcessingService.verifyOrder(request.orderId());
        return new PaidOrderResponse(result.order(), result.tickets(), result.paymentStatus());
    }

    @PostMapping("/webhook")
    @Operation(summary = "Verify signed PayChangu webhook notifications")
    public ResponseEntity<Void> webhook(
            @RequestHeader(value = "Signature", required = false) String signature,
            @RequestBody byte[] payload) {
        if (!payChanguService.isWebhookConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!payChanguService.isValidWebhookSignature(payload, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        JsonNode event;
        try {
            event = objectMapper.readTree(payload);
        } catch (IOException exception) {
            return ResponseEntity.badRequest().build();
        }
        JsonNode txRef = event == null ? null : event.get("tx_ref");
        if (txRef == null || !txRef.isTextual() || txRef.asText().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        paymentProcessingService.verifyTxRef(txRef.asText());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/callback")
    @Operation(summary = "Verify a PayChangu callback and redirect to the app")
    public void callback(@RequestParam("tx_ref") String txRef, HttpServletResponse response)
            throws IOException {
        redirect(txRef, bestEffortVerify(txRef), response);
    }

    @GetMapping("/return")
    @Operation(summary = "Verify the PayChangu return and redirect to the app")
    public void returnUrl(
            @RequestParam(value = "tx_ref", required = false) String txRef,
            @RequestParam(value = "status", required = false) String ignoredStatus,
            HttpServletResponse response) throws IOException {
        String verifiedStatus = txRef == null || txRef.isBlank()
                ? "pending"
                : bestEffortVerify(txRef);
        redirect(txRef, verifiedStatus, response);
    }

    private String bestEffortVerify(String txRef) {
        try {
            return paymentProcessingService.verifyTxRef(txRef);
        } catch (PaymentGatewayException | IllegalStateException exception) {
            logger.warn("Unable to verify PayChangu callback.");
            return "pending";
        } catch (NotFoundException exception) {
            logger.warn("PayChangu callback referenced an unknown transaction.");
            return "failed";
        }
    }

    private void redirect(String txRef, String status, HttpServletResponse response)
            throws IOException {
        String separator = appRedirectUri.contains("?") ? "&" : "?";
        String location = appRedirectUri + separator + "status="
                + UriUtils.encodeQueryParam(status, StandardCharsets.UTF_8);
        if (txRef != null && !txRef.isBlank()) {
            location += "&tx_ref=" + UriUtils.encodeQueryParam(txRef, StandardCharsets.UTF_8);
        }
        response.sendRedirect(location);
    }
}
