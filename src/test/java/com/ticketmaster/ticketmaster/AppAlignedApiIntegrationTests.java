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
import com.ticketmaster.ticketmaster.security.CustomUserDetailsService;
import com.ticketmaster.ticketmaster.security.JwtService;
import com.ticketmaster.ticketmaster.payment.PayChanguService;
import com.ticketmaster.ticketmaster.repository.PaymentRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CustomUserDetailsService userDetailsService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PaymentRepository paymentRepository;

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
                .andExpect(jsonPath("$.tickets.length()").value(2));

        mockMvc.perform(post("/api/payments/verify")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("orderId", orderId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tickets.length()").value(2));
        verify(payChanguService, times(1)).verify(txRef);
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
