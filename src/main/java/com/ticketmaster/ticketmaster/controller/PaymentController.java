package com.ticketmaster.ticketmaster.payment;

import com.ticketmaster.ticketmaster.service.PaymentService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PayChanguService payChanguService;

    public PaymentController(PaymentService paymentService, PayChanguService payChanguService) {
        this.paymentService = paymentService;
        this.payChanguService = payChanguService;
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestBody Map<String, Object> request) {
        Double amount = Double.valueOf(request.get("amount").toString());
        Long orderId = Long.valueOf(request.get("orderId").toString());
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        String checkoutUrl = payChanguService.initiateCheckout(amount, email, orderId);
        return ResponseEntity.ok(Map.of("checkoutUrl", checkoutUrl));
    }

    @GetMapping("/callback")
    public void callback(@RequestParam("tx_ref") String txRef, HttpServletResponse response) throws IOException {
        response.sendRedirect("myapp://payment-result?status=success&tx_ref=" + txRef);
    }

    @GetMapping("/return")
    public void returnUrl(@RequestParam(value = "tx_ref", required = false) String txRef,
                          @RequestParam(value = "status", defaultValue = "failed") String status,
                          HttpServletResponse response) throws IOException {
        response.sendRedirect("myapp://payment-result?status=" + status + "&tx_ref=" + txRef);
    }
}