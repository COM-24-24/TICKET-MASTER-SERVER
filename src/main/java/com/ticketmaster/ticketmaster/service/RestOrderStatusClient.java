package com.ticketmaster.ticketmaster.service;

import com.ticketmaster.ticketmaster.entity.PaymentStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.Map;

@Component
public class RestOrderStatusClient implements OrderStatusClient {
    private final RestClient restClient;

    public RestOrderStatusClient(RestClient.Builder builder,
                                 @Value("${services.order.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public void updateOrderStatus(Long orderId, PaymentStatus status) {
        // Point to the correct port (8080) and use the correct HTTP method
        restClient.post()
                .uri("/api/orders/{orderId}/status", orderId)
                .body(Map.of("status", status.name()))
                .retrieve()
                .toBodilessEntity();
    }
}