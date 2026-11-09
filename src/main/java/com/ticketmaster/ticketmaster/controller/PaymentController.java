package com.ticketmaster.ticketmaster.payment;

import com.ticketmaster.ticketmaster.common.exception.NotFoundException;
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
    private final String appRedirectUri;

    public PaymentController(
            PaymentProcessingService paymentProcessingService,
            @Value("${app.payment.app-redirect-uri:myapp://payment-result}") String appRedirectUri) {
        this.paymentProcessingService = paymentProcessingService;
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
        } catch (PaymentGatewayException | NotFoundException | IllegalStateException exception) {
            logger.warn("Unable to verify PayChangu callback.");
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
