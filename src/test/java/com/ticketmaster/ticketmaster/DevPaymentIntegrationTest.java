package com.ticketmaster.ticketmaster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketmaster.ticketmaster.events.entity.Event;
import com.ticketmaster.ticketmaster.events.entity.EventRepository;
import com.ticketmaster.ticketmaster.events.entity.EventStatus;
import com.ticketmaster.ticketmaster.events.entity.TicketType;
import com.ticketmaster.ticketmaster.events.entity.TicketTypeRepository;
import com.ticketmaster.ticketmaster.entity.PaymentProvider;
import com.ticketmaster.ticketmaster.repository.PaymentRepository;
import com.ticketmaster.ticketmaster.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:demo-payments;MODE=MariaDB;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=test-database-password",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=test-only-jwt-secret-key-with-at-least-32-characters",
        "google.client-id=test-only-google-client-id",
        "google.client-secret=test-only-google-client-secret",
        "paychangu.secret-key=test-only-paychangu-secret",
        "paychangu.base-url=http://localhost",
        "paychangu.callback-url=http://localhost/api/payments/callback",
        "paychangu.return-url=http://localhost/api/payments/return",
        "app.seed.enabled=false",
        "app.demo-payment.enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class DevPaymentIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private TicketTypeRepository ticketTypeRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Test
    void demoPaymentRequiresOrderOwnerAndIssuesTickets() throws Exception {
        JsonNode ownerAuth = register("demo-owner@example.test");
        JsonNode otherAuth = register("demo-other@example.test");
        var owner = userRepository.findByEmail("demo-owner@example.test").orElseThrow();
        Event event = new Event();
        event.setTitle("Demo Payment Event");
        event.setCategory("Music");
        event.setVenue("Main Hall");
        event.setLocation("Lilongwe");
        event.setStartTime(LocalDateTime.now().plusDays(3));
        event.setEndTime(LocalDateTime.now().plusDays(3).plusHours(2));
        event.setStatus(EventStatus.PUBLISHED);
        event.setOrganizerId(owner.getId());
        event.setOrganizerUsername(owner.getEmail());
        event = eventRepository.save(event);
        TicketType ticketType = new TicketType();
        ticketType.setEvent(event);
        ticketType.setName("Standard");
        ticketType.setPrice(new BigDecimal("500.00"));
        ticketType.setTotalQuantity(3);
        ticketType.setQuantitySold(0);
        ticketType = ticketTypeRepository.save(ticketType);

        JsonNode order = createOrder(ownerAuth, event.getId(), ticketType.getId());
        Long orderId = order.path("id").asLong();
        mockMvc.perform(post("/api/dev/orders/{orderId}/mark-paid", orderId)
                        .header("Authorization", bearer(otherAuth)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/dev/orders/{orderId}/mark-paid", orderId)
                        .header("Authorization", bearer(ownerAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("PAID"))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.tickets.length()").value(1));

        var payment = paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId).get(0);
        assertEquals(PaymentProvider.DEMO, payment.getProvider());
        assertEquals("PAID", payment.getStatus().name());
        assertTrue(payment.getTxRef().startsWith("DEMO-"));
    }

    private JsonNode register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "password", "password123"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode createOrder(JsonNode auth, Long eventId, Long ticketTypeId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header("Authorization", bearer(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "eventId", eventId,
                                "items", List.of(Map.of("ticketTypeId", ticketTypeId, "quantity", 1))))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(JsonNode auth) {
        return "Bearer " + auth.path("token").asText();
    }
}
