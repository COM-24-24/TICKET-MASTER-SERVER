package com.ticketmaster.ticketmaster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketmaster.ticketmaster.customer.CustomerProfile;
import com.ticketmaster.ticketmaster.customer.CustomerProfileRepository;
import com.ticketmaster.ticketmaster.entity.Payment;
import com.ticketmaster.ticketmaster.entity.PaymentStatus;
import com.ticketmaster.ticketmaster.events.entity.Event;
import com.ticketmaster.ticketmaster.events.entity.EventRepository;
import com.ticketmaster.ticketmaster.events.entity.EventStatus;
import com.ticketmaster.ticketmaster.events.entity.TicketType;
import com.ticketmaster.ticketmaster.events.entity.TicketTypeRepository;
import com.ticketmaster.ticketmaster.order.entity.Order;
import com.ticketmaster.ticketmaster.order.repository.OrderRepository;
import com.ticketmaster.ticketmaster.order.service.OrderService;
import com.ticketmaster.ticketmaster.security.CustomUserDetailsService;
import com.ticketmaster.ticketmaster.security.JwtService;
import com.ticketmaster.ticketmaster.payment.PayChanguService;
import com.ticketmaster.ticketmaster.repository.PaymentRepository;
import com.ticketmaster.ticketmaster.ticket.TicketRepository;
import com.ticketmaster.ticketmaster.user.entity.User;
import com.ticketmaster.ticketmaster.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.uploads-dir=target/test-uploads")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AppAlignedApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomerProfileRepository customerProfileRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private TicketTypeRepository ticketTypeRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CustomUserDetailsService userDetailsService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @MockBean
    private PayChanguService payChanguService;

    @Test
    void registrationDefaultsToCustomerReturnsUserAndRejectsDuplicateAndPrivilegedRoles() throws Exception {
        JsonNode registered = register("ada@example.test", "Ada Lovelace");
        long userId = registered.path("user").path("id").asLong();

        assertFalse(registered.path("token").asText().isBlank());
        assertEquals("Ada Lovelace", registered.path("user").path("name").asText());
        assertEquals("ada@example.test", registered.path("user").path("email").asText());
        assertEquals("CUSTOMER", registered.path("user").path("role").asText());
        assertEquals("Ada Lovelace", customerProfileRepository.findByUserId(userId)
                .orElseThrow().getFullName());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "ada@example.test",
                                "password", "password123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account with this email already exists."))
                .andExpect(jsonPath("$.path").value("/api/auth/register"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "organizer@example.test",
                                "password", "password123",
                                "role", "ORGANIZER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value("Only customer accounts can be created here."));
    }

    @Test
    void invalidLoginReturnsUnauthorizedJson() throws Exception {
        register("login@example.test", "Login User");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "login@example.test",
                                "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Incorrect email or password."))
                .andExpect(jsonPath("$.path").value("/api/auth/login"));
    }

    @Test
    void profileAutoCreatesForAuthenticatedEmailAndDoesNotCrossUsers() throws Exception {
        JsonNode alice = register("alice@example.test", "Alice Example");
        JsonNode bob = register("bob@example.test", "Bob Example");
        Long aliceId = alice.path("user").path("id").asLong();
        Long bobId = bob.path("user").path("id").asLong();
        CustomerProfile aliceProfile = customerProfileRepository.findByUserId(aliceId).orElseThrow();
        customerProfileRepository.delete(aliceProfile);

        mockMvc.perform(get("/api/customers/me")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(aliceId))
                .andExpect(jsonPath("$.fullName").value("alice"));

        mockMvc.perform(put("/api/customers/me")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Alice Updated","phone":null,"city":null,
                                 "latitude":null,"longitude":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(aliceId));

        assertEquals("Alice Updated", customerProfileRepository.findByUserId(aliceId)
                .orElseThrow().getFullName());
        assertEquals("Bob Example", customerProfileRepository.findByUserId(bobId)
                .orElseThrow().getFullName());
    }

    @Test
    void publicEventListFiltersAndReturnsAggregatedDtoShape() throws Exception {
        User organizer = saveUser("event-owner@example.test", User.Role.ORGANIZER);
        Event jazz = saveEvent(organizer, "Jazz Night", "Music", "Grand Hall",
                "Lilongwe", EventStatus.PUBLISHED, 3);
        jazz.setCoverImagePath("/uploads/events/jazz.png");
        eventRepository.save(jazz);
        saveTicketType(jazz, "Standard", "100.00", 50, 10);
        saveTicketType(jazz, "VIP", "250.00", 10, 0);

        saveEvent(organizer, "Football Final", "Sports", "National Stadium",
                "Blantyre", EventStatus.PUBLISHED, 4);
        saveEvent(organizer, "Cancelled Jazz", "Music", "Jazz Arena",
                "Mzuzu", EventStatus.CANCELLED, 5);
        saveEvent(organizer, "Draft Jazz", "Music", "Grand Hall",
                "Lilongwe", EventStatus.DRAFT, 6);

        MvcResult result = mockMvc.perform(get("/api/events")
                        .param("category", "mUsIc")
                        .param("search", "GRAND"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(1, response.size());
        JsonNode event = response.get(0);
        assertEquals("Jazz Night", event.path("title").asText());
        assertEquals("PUBLISHED", event.path("status").asText());
        assertEquals("AVAILABLE", event.path("availability").asText());
        assertEquals("/uploads/events/jazz.png", event.path("coverImagePath").asText());
        assertEquals(60, event.path("capacity").asLong());
        assertEquals(50, event.path("remainingTickets").asLong());
        assertEquals(0, new BigDecimal("100.00").compareTo(event.path("minPrice").decimalValue()));
        assertEquals(2, event.path("ticketTypes").size());
        assertEquals(40, event.path("ticketTypes").get(0).path("remaining").asInt());
        assertNotNull(LocalDateTime.parse(event.path("startTime").asText()));
    }

    @Test
    void publicEventListHidesDraftsAndDetailOnlyShowsDraftToOwner() throws Exception {
        JsonNode ownerResponse = register("draft-owner@example.test", "Draft Owner");
        JsonNode otherResponse = register("other-owner@example.test", "Other Owner");
        User owner = userRepository.findByEmail("draft-owner@example.test").orElseThrow();
        Event draft = saveEvent(owner, "Private Draft", "Other", "Studio",
                "Lilongwe", EventStatus.DRAFT, 2);

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get("/api/events/{id}", draft.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.path").value("/api/events/" + draft.getId()));

        mockMvc.perform(get("/api/events/{id}", draft.getId())
                        .header("Authorization", bearer(ownerResponse)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Private Draft"));

        mockMvc.perform(get("/api/events/{id}", draft.getId())
                        .header("Authorization", bearer(otherResponse)))
                .andExpect(status().isNotFound());
    }

    @Test
    void protectedRoutesAndOrderStatusRejectAnonymousRequests() throws Exception {
        assertAnonymousUnauthorized(get("/api/customers/me"), "/api/customers/me");
        assertAnonymousUnauthorized(get("/api/tickets/my"), "/api/tickets/my");
        assertAnonymousUnauthorized(get("/api/orders/my"), "/api/orders/my");
        assertAnonymousUnauthorized(post("/api/payments/checkout"), "/api/payments/checkout");
        assertAnonymousUnauthorized(post("/api/payments/verify"), "/api/payments/verify");
        assertAnonymousUnauthorized(get("/api/organizer/events"), "/api/organizer/events");

        assertAnonymousUnauthorized(post("/api/orders/123/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PAID\"}"), "/api/orders/123/status");
    }

    @Test
    void demoPaymentEndpointIsAbsentOutsideDevProfile() throws Exception {
        JsonNode customer = register("demo-disabled@example.test", "Demo Disabled");
        mockMvc.perform(post("/api/dev/orders/123/mark-paid")
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isNotFound());
    }

    @Test
    void checkoutUsesDatabaseAmountAndVerificationIssuesTicketsOnce() throws Exception {
        JsonNode customer = register("payment-owner@example.test", "Payment Owner");
        User owner = userRepository.findByEmail("payment-owner@example.test").orElseThrow();
        Event event = saveEvent(owner, "Payment Event", "Music", "Hall",
                "Lilongwe", EventStatus.PUBLISHED, 3);
        TicketType ticketType = saveTicketType(event, "Standard", "1250.00", 10, 0);
        String authorization = bearer(customer);
        JsonNode order = createOrder(authorization, event.getId(), ticketType.getId(), 2);
        Long orderId = order.path("id").asLong();

        when(payChanguService.initiateCheckout(
                any(BigDecimal.class), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn("https://checkout.example.test/session");
        MvcResult checkoutResult = mockMvc.perform(post("/api/payments/checkout")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("orderId", orderId, "amount", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.amount").value(2500.0))
                .andExpect(jsonPath("$.currency").value("MWK"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.checkoutUrl").value("https://checkout.example.test/session"))
                .andReturn();

        JsonNode checkout = objectMapper.readTree(checkoutResult.getResponse().getContentAsString());
        String txRef = checkout.path("txRef").asText();
        Payment payment = paymentRepository.findByTxRef(txRef).orElseThrow();
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertEquals("2500.0", payment.getAmount().toString());
        verify(payChanguService).initiateCheckout(
                new BigDecimal("2500.00"), "payment-owner@example.test",
                "Payment", "Owner", orderId, txRef);

        when(payChanguService.verify(txRef)).thenReturn(
                new PayChanguService.Verification(txRef, "success", "MWK", new BigDecimal("2500.00")));
        mockMvc.perform(post("/api/payments/verify")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("orderId", orderId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("PAID"))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.tickets.length()").value(2))
                .andExpect(jsonPath("$.tickets[0].qrContent")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyOrNullString())))
                .andExpect(jsonPath("$.tickets[0].buyerName").value("Payment Owner"))
                .andExpect(jsonPath("$.tickets[0].buyerEmail").value("payment-owner@example.test"))
                .andExpect(jsonPath("$.tickets[0].paymentReference").value(txRef))
                .andExpect(jsonPath("$.tickets[0].currency").value("MWK"));

        mockMvc.perform(get("/api/orders/{id}/tickets", orderId)
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].purchasedAt").exists());

        mockMvc.perform(post("/api/payments/verify")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("orderId", orderId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tickets.length()").value(2));
        verify(payChanguService, times(1)).verify(txRef);
    }

    @Test
    void orderRejectsForeignTicketTypesAndExcessQuantityAndProtectsOwnership() throws Exception {
        JsonNode ownerAuth = register("order-owner@example.test", "Order Owner");
        JsonNode otherAuth = register("order-other@example.test", "Order Other");
        User owner = userRepository.findByEmail("order-owner@example.test").orElseThrow();
        Event firstEvent = saveEvent(owner, "Order Event One", "Music", "Hall",
                "Lilongwe", EventStatus.PUBLISHED, 3);
        Event secondEvent = saveEvent(owner, "Order Event Two", "Music", "Arena",
                "Blantyre", EventStatus.PUBLISHED, 4);
        TicketType firstType = saveTicketType(firstEvent, "Standard", "120.00", 20, 0);
        TicketType secondType = saveTicketType(secondEvent, "VIP", "240.00", 20, 0);
        String ownerToken = bearer(ownerAuth);

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "eventId", firstEvent.getId(),
                                "items", List.of(Map.of(
                                        "ticketTypeId", secondType.getId(), "quantity", 1))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("does not belong")));

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "eventId", firstEvent.getId(),
                                "items", List.of(Map.of(
                                        "ticketTypeId", firstType.getId(), "quantity", 11))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("more than 10 tickets")));

        JsonNode order = createOrder(ownerToken, firstEvent.getId(), firstType.getId(), 1);
        mockMvc.perform(get("/api/orders/{id}", order.path("id").asLong())
                        .header("Authorization", bearer(otherAuth)))
                .andExpect(status().isForbidden());
    }

    @Test
    void expiringPendingOrderReleasesReservedStock() throws Exception {
        JsonNode customer = register("expiry-owner@example.test", "Expiry Owner");
        User owner = userRepository.findByEmail("expiry-owner@example.test").orElseThrow();
        Event event = saveEvent(owner, "Expiry Event", "Music", "Hall",
                "Lilongwe", EventStatus.PUBLISHED, 3);
        TicketType type = saveTicketType(event, "Standard", "100.00", 5, 0);
        JsonNode orderResponse = createOrder(bearer(customer), event.getId(), type.getId(), 2);
        Order order = orderRepository.findById(orderResponse.path("id").asLong()).orElseThrow();
        order.setCreatedAt(LocalDateTime.now().minusMinutes(60));
        orderRepository.saveAndFlush(order);

        orderService.expirePendingOrders();

        assertEquals(Order.OrderStatus.CANCELLED,
                orderRepository.findById(order.getId()).orElseThrow().getStatus());
        assertEquals(0, ticketTypeRepository.findById(type.getId()).orElseThrow().getQuantitySold());
    }

    @Test
    void paymentVerificationRejectsWrongTransactionReference() throws Exception {
        assertInvalidPaymentVerification("wrong-ref", "different-ref", "success", "MWK",
                new BigDecimal("100.00"), 409, "PENDING");
    }

    @Test
    void paymentVerificationRejectsWrongCurrency() throws Exception {
        assertInvalidPaymentVerification("wrong-currency", null, "success", "USD",
                new BigDecimal("100.00"), 409, "PENDING");
    }

    @Test
    void paymentVerificationRejectsAmountBelowOrderTotal() throws Exception {
        assertInvalidPaymentVerification("low-amount", null, "success", "MWK",
                new BigDecimal("99.99"), 409, "PENDING");
    }

    @Test
    void failedGatewayVerificationLeavesOrderPending() throws Exception {
        assertInvalidPaymentVerification("failed-payment", null, "failed", "MWK",
                new BigDecimal("100.00"), 200, "FAILED");
    }

    @Test
    void callbackThenVerifyIssuesTicketsOnlyOnce() throws Exception {
        JsonNode customer = register("callback-owner@example.test", "Callback Owner");
        User owner = userRepository.findByEmail("callback-owner@example.test").orElseThrow();
        Event event = saveEvent(owner, "Callback Event", "Music", "Hall",
                "Lilongwe", EventStatus.PUBLISHED, 3);
        TicketType type = saveTicketType(event, "Standard", "100.00", 5, 0);
        JsonNode order = createOrder(bearer(customer), event.getId(), type.getId(), 1);
        Long orderId = order.path("id").asLong();
        when(payChanguService.initiateCheckout(
                any(BigDecimal.class), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn("https://checkout.example.test/session");
        MvcResult checkout = mockMvc.perform(post("/api/payments/checkout")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("orderId", orderId))))
                .andExpect(status().isOk())
                .andReturn();
        String txRef = objectMapper.readTree(checkout.getResponse().getContentAsString())
                .path("txRef").asText();
        when(payChanguService.verify(txRef)).thenReturn(
                new PayChanguService.Verification(txRef, "success", "MWK", new BigDecimal("100.00")));

        mockMvc.perform(get("/api/payments/callback").param("tx_ref", txRef))
                .andExpect(status().isFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("myapp://payment-result?status=success&tx_ref=" + txRef));
        MvcResult verified = mockMvc.perform(post("/api/payments/verify")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("orderId", orderId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tickets.length()").value(1))
                .andReturn();
        String ticketCode = objectMapper.readTree(verified.getResponse().getContentAsString())
                .path("tickets").get(0).path("ticketCode").asText();
        User validator = saveUser("ticket-validator@example.test", User.Role.VALIDATOR);
        String validatorToken = "Bearer " + jwtService.generateToken(
                userDetailsService.loadUserByUsername(validator.getEmail()));
        String validationRequest = json(Map.of("ticketCode", ticketCode));
        mockMvc.perform(post("/api/tickets/validate")
                        .header("Authorization", validatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("USED"));
        mockMvc.perform(post("/api/tickets/validate")
                        .header("Authorization", validatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationRequest))
                .andExpect(status().isConflict());
        assertEquals(1, ticketRepository.count());
        verify(payChanguService, times(1)).verify(txRef);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentOrdersCannotReserveMoreThanAvailableStock() throws Exception {
        JsonNode customer = register("concurrent-owner@example.test", "Concurrent Owner");
        User owner = userRepository.findByEmail("concurrent-owner@example.test").orElseThrow();
        Event event = saveEvent(owner, "Concurrent Event", "Music", "Hall",
                "Lilongwe", EventStatus.PUBLISHED, 3);
        TicketType type = saveTicketType(event, "Single", "100.00", 1, 0);
        String token = bearer(customer);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> placeConcurrentOrder(
                    start, token, event.getId(), type.getId()));
            Future<Integer> second = executor.submit(() -> placeConcurrentOrder(
                    start, token, event.getId(), type.getId()));
            start.countDown();
            List<Integer> statuses = List.of(first.get(), second.get());
            assertEquals(1L, statuses.stream().filter(code -> code == 200).count());
            assertEquals(1L, statuses.stream().filter(code -> code == 400).count());
            assertEquals(1, ticketTypeRepository.findById(type.getId()).orElseThrow().getQuantitySold());
            assertTrue(ticketTypeRepository.findById(type.getId()).orElseThrow().getQuantitySold()
                    <= type.getTotalQuantity());
        } finally {
            executor.shutdownNow();
            orderRepository.deleteAll(orderRepository.findByUserIdOrderByCreatedAtDesc(owner.getId()));
            ticketTypeRepository.delete(type);
            eventRepository.delete(event);
            customerProfileRepository.findByUserId(owner.getId()).ifPresent(customerProfileRepository::delete);
            userRepository.delete(owner);
        }
    }

    @Test
    void unknownOrderTicketAndTicketTypeReturnNotFound() throws Exception {
        JsonNode customer = register("missing-resource@example.test", "Customer");
        String authorization = bearer(customer);

        mockMvc.perform(get("/api/orders/9999").header("Authorization", authorization))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Order not found."));
        mockMvc.perform(get("/api/tickets/9999").header("Authorization", authorization))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Ticket not found."));
        mockMvc.perform(get("/api/ticket-types/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Ticket type not found."));
    }

    @Test
    void healthIsPublicAndMalformedJsonUsesTheStandardErrorShape() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body."))
                .andExpect(jsonPath("$.path").value("/api/auth/login"));
    }

    @Test
    void creatingAnEventUsesTheAuthenticatedUsersId() throws Exception {
        JsonNode auth = register("new-event@example.test", "Event Owner");
        Long userId = auth.path("user").path("id").asLong();
        String body = json(Map.of(
                "title", "New Event",
                "category", "Music",
                "venue", "Main Hall",
                "location", "Lilongwe",
                "startTime", LocalDateTime.now().plusDays(2).withNano(0).toString(),
                "endTime", LocalDateTime.now().plusDays(2).plusHours(2).withNano(0).toString()));

        mockMvc.perform(post("/api/events")
                        .header("Authorization", bearer(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizerId").value(userId))
                .andExpect(jsonPath("$.organizerUsername").value("new-event@example.test"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void organizerCanUploadOnlyImageContentToOwnedEvent() throws Exception {
        User organizer = saveUser("image-owner@example.test", User.Role.ORGANIZER);
        Event event = saveEvent(organizer, "Image Event", "Other", "Studio",
                "Lilongwe", EventStatus.DRAFT, 4);
        String token = jwtService.generateToken(
                userDetailsService.loadUserByUsername(organizer.getEmail()));
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/ljsAAAAASUVORK5CYII=");
        Path eventsDirectory = Path.of("target", "test-uploads", "events").toAbsolutePath();
        boolean eventsDirectoryExisted = Files.exists(eventsDirectory);
        boolean uploadDirectoryExisted = Files.exists(eventsDirectory.getParent());
        Path storedImage = null;

        try {
            MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .multipart("/api/organizer/events/{eventId}/image", event.getId())
                            .file(new MockMultipartFile("file", "cover.png", "image/png", png))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.coverImagePath").value(
                            org.hamcrest.Matchers.startsWith("/uploads/events/")))
                    .andReturn();

            JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
            String relativePath = response.path("coverImagePath").asText();
            storedImage = eventsDirectory.resolve(relativePath.substring(
                    relativePath.lastIndexOf('/') + 1));
            assertEquals(png.length, Files.size(storedImage));
            assertEquals(relativePath, eventRepository.findById(event.getId())
                    .orElseThrow().getCoverImagePath());
            mockMvc.perform(get(relativePath))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(png));

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .multipart("/api/organizer/events/{eventId}/image", event.getId())
                            .file(new MockMultipartFile("file", "not-image.png", "image/png",
                                    "not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Only JPG and PNG images are supported."));
        } finally {
            if (storedImage != null) {
                Files.deleteIfExists(storedImage);
            }
            if (!eventsDirectoryExisted && Files.exists(eventsDirectory)) {
                try (var files = Files.list(eventsDirectory)) {
                    if (files.findAny().isEmpty()) {
                        Files.deleteIfExists(eventsDirectory);
                    }
                }
            }
            if (!uploadDirectoryExisted && Files.exists(eventsDirectory.getParent())) {
                try (var files = Files.list(eventsDirectory.getParent())) {
                    if (files.findAny().isEmpty()) {
                        Files.deleteIfExists(eventsDirectory.getParent());
                    }
                }
            }
        }
    }

    private JsonNode register(String email, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", email,
                                "password", "password123",
                                "name", name))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private User saveUser(String email, User.Role role) {
        return userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode("password123"))
                .role(role)
                .build());
    }

    private Event saveEvent(User owner, String title, String category, String venue,
            String location, EventStatus status, int dayOffset) {
        Event event = new Event();
        event.setTitle(title);
        event.setCategory(category);
        event.setVenue(venue);
        event.setLocation(location);
        event.setStartTime(LocalDateTime.now().plusDays(dayOffset).withNano(0));
        event.setEndTime(LocalDateTime.now().plusDays(dayOffset).plusHours(2).withNano(0));
        event.setStatus(status);
        event.setOrganizerId(owner.getId());
        event.setOrganizerUsername(owner.getEmail());
        return eventRepository.save(event);
    }

    private TicketType saveTicketType(Event event, String name, String price, int capacity, int sold) {
        TicketType ticketType = new TicketType();
        ticketType.setEvent(event);
        ticketType.setName(name);
        ticketType.setPrice(new BigDecimal(price));
        ticketType.setTotalQuantity(capacity);
        ticketType.setQuantitySold(sold);
        return ticketTypeRepository.save(ticketType);
    }

    private JsonNode createOrder(String authorization, Long eventId, Long ticketTypeId, int quantity)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "eventId", eventId,
                                "items", List.of(Map.of(
                                        "ticketTypeId", ticketTypeId,
                                        "quantity", quantity))))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertInvalidPaymentVerification(
            String suffix, String returnedTxRef, String gatewayStatus, String currency,
            BigDecimal amount, int expectedHttpStatus, String expectedPaymentStatus) throws Exception {
        JsonNode customer = register(suffix + "@example.test", "Verification Owner");
        User owner = userRepository.findByEmail(suffix + "@example.test").orElseThrow();
        Event event = saveEvent(owner, "Verify Event " + suffix, "Music", "Hall",
                "Lilongwe", EventStatus.PUBLISHED, 3);
        TicketType type = saveTicketType(event, "Standard", "100.00", 3, 0);
        JsonNode order = createOrder(bearer(customer), event.getId(), type.getId(), 1);
        Long orderId = order.path("id").asLong();
        when(payChanguService.initiateCheckout(
                any(BigDecimal.class), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn("https://checkout.example.test/session");
        MvcResult checkout = mockMvc.perform(post("/api/payments/checkout")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("orderId", orderId))))
                .andExpect(status().isOk())
                .andReturn();
        String txRef = objectMapper.readTree(checkout.getResponse().getContentAsString())
                .path("txRef").asText();
        when(payChanguService.verify(txRef)).thenReturn(
                new PayChanguService.Verification(
                        returnedTxRef == null ? txRef : returnedTxRef,
                        gatewayStatus, currency, amount));

        var result = mockMvc.perform(post("/api/payments/verify")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("orderId", orderId))))
                .andExpect(status().is(expectedHttpStatus))
                .andReturn();
        if (expectedHttpStatus == 200) {
            assertEquals(expectedPaymentStatus,
                    objectMapper.readTree(result.getResponse().getContentAsString())
                            .path("paymentStatus").asText());
        } else {
            assertEquals("Payment verification did not match this order.",
                    objectMapper.readTree(result.getResponse().getContentAsString())
                            .path("message").asText());
        }
    }

    private int placeConcurrentOrder(
            CountDownLatch start, String authorization, Long eventId, Long ticketTypeId) throws Exception {
        start.await();
        return mockMvc.perform(post("/api/orders")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "eventId", eventId,
                                "items", List.of(Map.of(
                                        "ticketTypeId", ticketTypeId, "quantity", 1))))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private void assertAnonymousUnauthorized(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String path) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value(path));
    }

    private String bearer(JsonNode authResponse) {
        return "Bearer " + authResponse.path("token").asText();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
