package com.ticketmaster.ticketmaster.controller;

import com.ticketmaster.ticketmaster.payment.PaymentProcessingService;
import com.ticketmaster.ticketmaster.payment.dto.PaidOrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriUtils;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/dev")
@Profile("dev")
@ConditionalOnProperty(name = "app.demo-payment.enabled", havingValue = "true")
public class DevPaymentController {

    private static final Logger logger = LoggerFactory.getLogger(DevPaymentController.class);
    private final PaymentProcessingService paymentProcessingService;
    private final String appRedirectUri;

    public DevPaymentController(PaymentProcessingService paymentProcessingService,
            @Value("${app.payment.app-redirect-uri:myapp://payment-result}") String appRedirectUri) {
        this.paymentProcessingService = paymentProcessingService;
        this.appRedirectUri = appRedirectUri;
    }

    @PostMapping("/orders/{orderId}/mark-paid")
    @Operation(summary = "Mark an owned pending order paid using the development demo provider")
    public PaidOrderResponse markPaid(@PathVariable Long orderId) {
        logger.warn("Development demo payment used for order {}.", orderId);
        PaymentProcessingService.PaidOrderResult result =
                paymentProcessingService.markDemoOrderPaid(orderId);
        return new PaidOrderResponse(result.order(), result.tickets(), result.paymentStatus());
    }

    @GetMapping(value = "/payments/checkout/{txRef}", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "Display the local fake PayChangu checkout used for student testing")
    public ResponseEntity<String> hostedCheckout(@PathVariable String txRef) {
        PaymentProcessingService.DemoCheckout checkout =
                paymentProcessingService.getDemoCheckout(txRef);
        String action = "/api/dev/payments/return?tx_ref="
                + UriUtils.encodeQueryParam(checkout.txRef(), StandardCharsets.UTF_8);
        String amount = checkout.amount().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String body = """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>PayChangu Demo Checkout</title>
                <style>body{font:16px system-ui;background:#f4f6f8;margin:0;display:grid;min-height:100vh;place-items:center;color:#17202a}.card{background:white;padding:2rem;border-radius:16px;max-width:420px;box-shadow:0 8px 32px #0002}button{background:#087f5b;color:white;border:0;border-radius:8px;padding:14px 18px;font-size:1rem;width:100%%;cursor:pointer}.badge{color:#a15c00;background:#fff3cd;padding:8px;border-radius:6px}</style></head>
                <body><main class="card"><p class="badge">Student testing only · No money will be charged</p>
                <h1>PayChangu Demo</h1><p>Order payment amount: <strong>MWK %s</strong></p>
                <p>This local simulator stands in for the hosted PayChangu page.</p>
                <form method="post" action="%s"><button type="submit">Simulate successful payment</button></form>
                </main></body></html>
                """.formatted(amount, action);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.TEXT_HTML).body(body);
    }

    @PostMapping("/payments/return")
    @Operation(summary = "Complete the fake payment and redirect to the student app")
    public void fakeReturn(@RequestParam("tx_ref") String txRef, HttpServletResponse response)
            throws IOException {
        paymentProcessingService.completeDemoPayment(txRef);
        logger.info("Student demo payment completed.");
        String separator = appRedirectUri.contains("?") ? "&" : "?";
        String location = appRedirectUri + separator + "status=success&tx_ref="
                + UriUtils.encodeQueryParam(txRef, StandardCharsets.UTF_8);
        response.sendRedirect(location);
    }
}
