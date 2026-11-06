package com.ticketmaster.ticketmaster.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class PayChanguService {

    @Value("${paychangu.secret-key}")
    private String secretKey;

    @Value("${paychangu.base-url}")
    private String baseUrl;

    @Value("${paychangu.callback-url}")
    private String callbackUrl;

    @Value("${paychangu.return-url}")
    private String returnUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public String initiateCheckout(Double amount, String email, Long orderId) {
        String txRef = UUID.randomUUID().toString();

        Map<String, Object> body = new HashMap<>();
        body.put("amount", String.valueOf(amount));
        body.put("currency", "MWK");
        body.put("email", email);
        body.put("first_name", "Customer");
        body.put("last_name", "User");
        body.put("tx_ref", txRef);
        body.put("callback_url", callbackUrl);
        body.put("return_url", returnUrl);

        Map<String, String> custom = new HashMap<>();
        custom.put("title", "Event Ticket Purchase");
        custom.put("description", "Event ticket payment");
        body.put("customization", custom);

        Map<String, String> meta = new HashMap<>();
        meta.put("order_id", String.valueOf(orderId));
        body.put("meta", meta);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(secretKey);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    baseUrl + "/payment", request, Map.class);

            Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
            return (String) data.get("checkout_url");

        } catch (Exception e) {
            throw new RuntimeException("PayChangu checkout failed: " + e.getMessage());
        }
    }
}