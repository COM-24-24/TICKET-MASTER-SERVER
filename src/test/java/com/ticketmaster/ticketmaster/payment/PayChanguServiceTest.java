package com.ticketmaster.ticketmaster.payment;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class PayChanguServiceTest {

    @Test
    void initiateCheckoutUsesStandardCheckoutRequestAndReturnsUrl() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://api.paychangu.com/payment"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sandbox-secret"))
                .andExpect(jsonPath("$.amount").value("1000.00"))
                .andExpect(jsonPath("$.currency").value("MWK"))
                .andExpect(jsonPath("$.callback_url").value("https://app.example/callback"))
                .andExpect(jsonPath("$.return_url").value("https://app.example/return"))
                .andExpect(jsonPath("$.tx_ref").value("order-ref"))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"checkout_url":"https://checkout.paychangu.com/session"}}
                        """, MediaType.APPLICATION_JSON));
        PayChanguService service = service(restTemplate, "webhook-secret");

        String checkoutUrl = service.initiateCheckout(
                new BigDecimal("1000.00"), "buyer@example.test", "Test", "Buyer", 12L, "order-ref");

        assertEquals("https://checkout.paychangu.com/session", checkoutUrl);
        server.verify();
    }

    @Test
    void initiateCheckoutRejectsMissingCheckoutUrl() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(ExpectedCount.once(), requestTo("https://api.paychangu.com/payment"))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"status":"pending"}}
                        """, MediaType.APPLICATION_JSON));
        PayChanguService service = service(restTemplate, "webhook-secret");

        assertThrows(PaymentGatewayException.class, () -> service.initiateCheckout(
                new BigDecimal("1000.00"), "buyer@example.test", "Test", "Buyer", 12L, "order-ref"));
        server.verify();
    }

    @Test
    void initiateCheckoutMapsInvalidCredentialsToGatewayFailure() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://api.paychangu.com/payment"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Invalid API key\"}"));
        PayChanguService service = service(restTemplate, "webhook-secret");

        assertThrows(PaymentGatewayException.class, () -> service.initiateCheckout(
                new BigDecimal("1000.00"), "buyer@example.test", "Test", "Buyer", 12L, "order-ref"));
        server.verify();
    }

    @Test
    void verifiesTransactionsThroughTheDocumentedEndpoint() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://api.paychangu.com/verify-payment/order-ref"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer sandbox-secret"))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"tx_ref":"order-ref","status":"success",
                        "currency":"MWK","amount":1000}}
                        """, MediaType.APPLICATION_JSON));
        PayChanguService service = service(restTemplate, "webhook-secret");

        PayChanguService.Verification verification = service.verify("order-ref");

        assertEquals("order-ref", verification.txRef());
        assertEquals("success", verification.status());
        assertEquals("MWK", verification.currency());
        assertEquals(new BigDecimal("1000"), verification.amount());
        server.verify();
    }

    @Test
    void validatesWebhookHmacAndRejectsMissingOrIncorrectSignatures() throws Exception {
        byte[] payload = "{\"tx_ref\":\"order-ref\"}".getBytes(StandardCharsets.UTF_8);
        PayChanguService service = service(new RestTemplate(), "webhook-secret");
        PayChanguService unconfigured = service(new RestTemplate(), "");

        assertTrue(service.isWebhookConfigured());
        assertTrue(service.isValidWebhookSignature(payload, signature(payload, "webhook-secret")));
        assertFalse(service.isValidWebhookSignature(payload, "invalid"));
        assertFalse(service.isValidWebhookSignature(payload, null));
        assertFalse(unconfigured.isWebhookConfigured());
        assertFalse(unconfigured.isValidWebhookSignature(payload, signature(payload, "webhook-secret")));
    }

    private PayChanguService service(RestTemplate restTemplate, String webhookSecret) {
        return new PayChanguService(
                "sandbox-secret",
                "https://api.paychangu.com",
                "https://app.example/callback",
                "https://app.example/return",
                webhookSecret,
                restTemplate);
    }

    private String signature(byte[] payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload));
    }
}
