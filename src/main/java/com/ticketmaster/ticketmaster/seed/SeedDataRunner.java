package com.ticketmaster.ticketmaster.seed;

import com.ticketmaster.ticketmaster.customer.CustomerProfile;
import com.ticketmaster.ticketmaster.customer.CustomerProfileRepository;
import com.ticketmaster.ticketmaster.events.entity.Event;
import com.ticketmaster.ticketmaster.events.entity.EventRepository;
import com.ticketmaster.ticketmaster.events.entity.EventStatus;
import com.ticketmaster.ticketmaster.events.entity.TicketType;
import com.ticketmaster.ticketmaster.events.entity.TicketTypeRepository;
import com.ticketmaster.ticketmaster.user.entity.User;
import com.ticketmaster.ticketmaster.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Component
@Profile("dev")
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class SeedDataRunner implements CommandLineRunner {

    private static final String DEV_PASSWORD = "Password123";

    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final EventRepository eventRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final PasswordEncoder passwordEncoder;
    private final Path uploadsDirectory;

    public SeedDataRunner(
            UserRepository userRepository,
            CustomerProfileRepository customerProfileRepository,
            EventRepository eventRepository,
            TicketTypeRepository ticketTypeRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.uploads-dir:uploads}") String uploadsDirectory) {
        this.userRepository = userRepository;
        this.customerProfileRepository = customerProfileRepository;
        this.eventRepository = eventRepository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.passwordEncoder = passwordEncoder;
        this.uploadsDirectory = Path.of(uploadsDirectory);
    }

    @Override
    @Transactional
    public void run(String... args) {
        User customer = ensureUser("customer@test.com", User.Role.CUSTOMER);
        ensureUser("organizer@test.com", User.Role.ORGANIZER);
        User organizer = userRepository.findByEmail("organizer@test.com").orElseThrow();
        ensureUser("validator@test.com", User.Role.VALIDATOR);
        if (!customerProfileRepository.existsByUserId(customer.getId())) {
            customerProfileRepository.save(new CustomerProfile(customer.getId(), "Test Customer"));
        }

        List<EventSeed> seeds = List.of(
                new EventSeed("Blantyre Football Cup", "Sports", "Kamuzu Stadium",
                        "Blantyre", 8_000, 12),
                new EventSeed("ZaMusic Festival", "Concerts", "BICC",
                        "Lilongwe", 20_000, 26),
                new EventSeed("Lilongwe Drama Festival", "Festival", "Bingu National Stadium",
                        "Lilongwe", 5_000, 40),
                new EventSeed("Botanic Garden Workshop", "Workshop", "Botanic Garden",
                        "Lilongwe", 3_000, 54),
                new EventSeed("Bonanza League", "Sports", "Cival Stadium",
                        "Lilongwe", 10_000, 68),
                new EventSeed("AfroFusion Live 2026", "Concerts", "Comesa Hall",
                        "Blantyre", 0, 82));
        for (EventSeed seed : seeds) {
            ensureEvent(organizer, seed);
        }
        System.out.println("Development accounts seeded: customer@test.com, organizer@test.com, validator@test.com");
    }

    private User ensureUser(String email, User.Role role) {
        return userRepository.findByEmail(email).orElseGet(() ->
                userRepository.save(User.builder()
                        .email(email)
                        .password(passwordEncoder.encode(DEV_PASSWORD))
                        .role(role)
                        .build()));
    }

    private void ensureEvent(User organizer, EventSeed seed) {
        String fileName = slug(seed.title()) + ".jpg";
        String coverPath = "/uploads/events/" + fileName;
        createCoverIfMissing(fileName, seed.title());

        Event event = eventRepository.findFirstByTitle(seed.title()).orElseGet(() -> {
            LocalDateTime start = LocalDateTime.now().plusDays(seed.dayOffset()).withHour(18)
                    .withMinute(0).withSecond(0).withNano(0);
            Event created = new Event();
            created.setOrganizerId(organizer.getId());
            created.setOrganizerUsername(organizer.getEmail());
            created.setTitle(seed.title());
            created.setCategory(seed.category());
            created.setVenue(seed.venue());
            created.setLocation(seed.location());
            created.setStartTime(start);
            created.setEndTime(start.plusHours(3));
            created.setStatus(EventStatus.PUBLISHED);
            created.setCoverImagePath(coverPath);
            return eventRepository.save(created);
        });

        if (event.getCoverImagePath() == null || event.getCoverImagePath().isBlank()) {
            event.setCoverImagePath(coverPath);
            eventRepository.save(event);
        }
        List<TicketType> existingTypes = ticketTypeRepository.findByEventId(event.getId());
        if (seed.title().equals("AfroFusion Live 2026")) {
            ensureTicketType(event, existingTypes, "Standard", "15000.00", 15_000);
            ensureTicketType(event, existingTypes, "VIP", "30000.00", 30_000);
        } else {
            int standardStock = seed.stock() * 9 / 10;
            ensureTicketType(event, existingTypes, "Standard", "5000.00", standardStock);
            ensureTicketType(event, existingTypes, "VIP", "10000.00", seed.stock() - standardStock);
        }
    }

    private void ensureTicketType(
            Event event, List<TicketType> existingTypes, String name, String price, int stock) {
        if (existingTypes.stream().anyMatch(type -> type.getName().equals(name))) {
            return;
        }
        TicketType ticketType = new TicketType();
        ticketType.setEvent(event);
        ticketType.setName(name);
        ticketType.setPrice(new BigDecimal(price));
        ticketType.setTotalQuantity(stock);
        ticketType.setQuantitySold(0);
        ticketTypeRepository.save(ticketType);
    }

    private void createCoverIfMissing(String fileName, String title) {
        Path eventsDirectory = uploadsDirectory.resolve("events");
        Path cover = eventsDirectory.resolve(fileName);
        if (Files.exists(cover)) {
            return;
        }
        try {
            Files.createDirectories(eventsDirectory);
            System.setProperty("java.awt.headless", "true");
            BufferedImage image = new BufferedImage(800, 450, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(new Color(24, 45, 70));
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.setColor(new Color(70, 145, 190));
                graphics.fillRect(0, 340, image.getWidth(), 110);
                graphics.setColor(Color.WHITE);
                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
                graphics.drawString(title, 36, 235);
            } finally {
                graphics.dispose();
            }
            if (!ImageIO.write(image, "jpg", cover.toFile())) {
                throw new IOException("No JPEG writer is available.");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create development event cover.", exception);
        }
    }

    private String slug(String title) {
        return title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private record EventSeed(
            String title, String category, String venue, String location, int stock, int dayOffset) {
    }
}
