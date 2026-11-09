package com.ticketmaster.ticketmaster.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
public class PayChanguService {

    private final String secretKey;
    private final String baseUrl;
    private final String callbackUrl;
    private final String returnUrl;
    private final RestTemplate restTemplate;

    public PayChanguService(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${paychangu.secret-key}") String secretKey,
            @Value("${paychangu.base-url}") String baseUrl,
            @Value("${paychangu.callback-url}") String callbackUrl,
            @Value("${paychangu.return-url}") String returnUrl) {
        this.secretKey = secretKey;
        this.baseUrl = baseUrl;
        this.callbackUrl = callbackUrl;
        this.returnUrl = returnUrl;
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();
    }

    public String initiateCheckout(
            BigDecimal amount, String email, String firstName, String lastName,
            Long orderId, String txRef) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", amount.toPlainString());
        body.put("currency", "MWK");
        body.put("email", email);
        body.put("first_name", firstName);
        body.put("last_name", lastName);
        body.put("tx_ref", txRef);
        body.put("callback_url", callbackUrl);
        body.put("return_url", returnUrl);

        Map<String, String> custom = new HashMap<>();
        custom.put("title", "Event Ticket Purchase");
        custom.put("description", "Event ticket payment");
        body.put("customization", custom);

        body.put("meta", Map.of("order_id", String.valueOf(orderId)));
        ResponseEntity<Map> response;
        try {
            response = restTemplate.postForEntity(
                    baseUrl + "/payment", new HttpEntity<>(body, headers()), Map.class);
        } catch (RestClientException exception) {
            throw new PaymentGatewayException(exception);
        }

        Object data = response.getBody() == null ? null : response.getBody().get("data");
        if (!(data instanceof Map<?, ?> dataMap)
                || !(dataMap.get("checkout_url") instanceof String checkoutUrl)
                || checkoutUrl.isBlank()) {
            throw new PaymentGatewayException();
        }
        return checkoutUrl;
    }

    public Verification verify(String txRef) {
        String encodedTxRef = UriUtils.encodePathSegment(txRef, StandardCharsets.UTF_8);
        ResponseEntity<Map> response;
        try {
            response = restTemplate.exchange(
                    baseUrl + "/verify-payment/" + encodedTxRef,
                    HttpMethod.GET,
                    new HttpEntity<>(headers()),
                    Map.class,
                    new Object[0]);
        } catch (RestClientException exception) {
            throw new PaymentGatewayException(exception);
        }

        Map<?, ?> body = response.getBody();
        Object data = body == null ? null : body.get("data");
        if (!(data instanceof Map<?, ?> values)) {
            throw new PaymentGatewayException();
        }
        try {
            String returnedTxRef = requiredText(values, "tx_ref");
            String status = requiredText(values, "status");
            String currency = requiredText(values, "currency");
            BigDecimal amount = new BigDecimal(requiredText(values, "amount"));
            return new Verification(returnedTxRef, status, currency, amount);
        } catch (IllegalArgumentException exception) {
            throw new PaymentGatewayException(exception);
        }
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(secretKey);
        return headers;
    }

    private String requiredText(Map<?, ?> values, String name) {
        Object value = values.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing gateway field: " + name);
        }
        return value.toString();
    }

    public record Verification(String txRef, String status, String currency, BigDecimal amount) {
    }
}
