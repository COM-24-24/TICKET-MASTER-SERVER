package com.ticketmaster.ticketmaster.controller;

import com.ticketmaster.ticketmaster.payment.PaymentProcessingService;
import com.ticketmaster.ticketmaster.payment.dto.PaidOrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dev/orders")
@Profile("dev")
@ConditionalOnProperty(name = "app.demo-payment.enabled", havingValue = "true")
public class DevPaymentController {

    private static final Logger logger = LoggerFactory.getLogger(DevPaymentController.class);
    private final PaymentProcessingService paymentProcessingService;

    public DevPaymentController(PaymentProcessingService paymentProcessingService) {
        this.paymentProcessingService = paymentProcessingService;
    }

    @PostMapping("/{orderId}/mark-paid")
    @Operation(summary = "Mark an owned pending order paid using the development demo provider")
    public PaidOrderResponse markPaid(@PathVariable Long orderId) {
        logger.warn("Development demo payment used for order {}.", orderId);
        PaymentProcessingService.PaidOrderResult result =
                paymentProcessingService.markDemoOrderPaid(orderId);
        return new PaidOrderResponse(result.order(), result.tickets(), result.paymentStatus());
    }
}
