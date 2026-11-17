package com.ticketmaster.ticketmaster.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class PayChanguService {

    private final String secretKey;
    private final String baseUrl;
    private final String callbackUrl;
    private final String returnUrl;
    private final String webhookSecret;
    private final RestTemplate restTemplate;

    @Autowired
    public PayChanguService(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${paychangu.secret-key}") String secretKey,
            @Value("${paychangu.base-url}") String baseUrl,
            @Value("${paychangu.callback-url}") String callbackUrl,
            @Value("${paychangu.return-url}") String returnUrl,
            @Value("${paychangu.webhook-secret:}") String webhookSecret) {
        this(secretKey, baseUrl, callbackUrl, returnUrl, webhookSecret,
                restTemplateBuilder.setConnectTimeout(Duration.ofSeconds(5))
                        .setReadTimeout(Duration.ofSeconds(15))
                        .build());
    }

    PayChanguService(
            String secretKey, String baseUrl, String callbackUrl, String returnUrl,
            String webhookSecret, RestTemplate restTemplate) {
        this.secretKey = secretKey;
        this.baseUrl = baseUrl;
        this.callbackUrl = callbackUrl;
        this.returnUrl = returnUrl;
        this.webhookSecret = webhookSecret;
        this.restTemplate = restTemplate;
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

        Map<?, ?> responseBody = response.getBody();
        if (responseBody == null || !"success".equalsIgnoreCase(
                String.valueOf(responseBody.get("status")))) {
            throw new PaymentGatewayException();
        }
        Object data = responseBody.get("data");
        if (!(data instanceof Map<?, ?> dataMap)
                || !(dataMap.get("checkout_url") instanceof String checkoutUrl)
                || checkoutUrl.isBlank()) {
            throw new PaymentGatewayException();
        }
        return requirePayChanguCheckoutUrl(checkoutUrl);
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

    public boolean isWebhookConfigured() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }

    public boolean isValidWebhookSignature(byte[] payload, String signature) {
        if (!isWebhookConfigured() || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = java.util.HexFormat.of().formatHex(mac.doFinal(payload))
                    .getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to validate PayChangu webhook signature.", exception);
        }
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
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

    private String requirePayChanguCheckoutUrl(String checkoutUrl) {
        try {
            URI uri = URI.create(checkoutUrl);
            String host = uri.getHost();
            String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
            boolean isPayChanguHost = normalizedHost.equals("paychangu.com")
                    || normalizedHost.endsWith(".paychangu.com");
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !isPayChanguHost
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new PaymentGatewayException(
                        new IllegalArgumentException("PayChangu returned a non-secure checkout URL."));
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException exception) {
            throw new PaymentGatewayException(exception);
        }
    }

    public record Verification(String txRef, String status, String currency, BigDecimal amount) {
    }
}
