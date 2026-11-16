package com.ticketmaster.ticketmaster.seed;

import com.ticketmaster.ticketmaster.customer.CustomerProfileRepository;
import com.ticketmaster.ticketmaster.events.entity.EventRepository;
import com.ticketmaster.ticketmaster.events.entity.TicketTypeRepository;
import com.ticketmaster.ticketmaster.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import javax.imageio.ImageIO;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SeedDataRunnerTest {

    @Autowired private UserRepository userRepository;
    @Autowired private CustomerProfileRepository customerProfileRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private TicketTypeRepository ticketTypeRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @TempDir
    Path tempDirectory;

    @Test
    void runningTwiceDoesNotDuplicateSeedData() throws Exception {
        SeedDataRunner runner = new SeedDataRunner(
                userRepository, customerProfileRepository, eventRepository,
                ticketTypeRepository, passwordEncoder, tempDirectory.toString());

        runner.run();
        runner.run();

        assertEquals(3, userRepository.count());
        assertEquals(1, customerProfileRepository.count());
        assertEquals(6, eventRepository.count());
        assertEquals(12, ticketTypeRepository.count());
        assertTrue(passwordEncoder.matches("Password123",
                userRepository.findByEmail("customer@test.com").orElseThrow().getPassword()));
        assertTrue(java.nio.file.Files.exists(
                tempDirectory.resolve("events/blantyre-football-cup.jpg")));
        BufferedImage cover = ImageIO.read(tempDirectory.resolve("events/blantyre-football-cup.jpg").toFile());
        assertEquals(1000, cover.getWidth());
        assertEquals(1000, cover.getHeight());
        assertTrue(cover.getRGB(0, 0) != cover.getRGB(500, 950),
                "The lower accent band should be decorative and distinct from the navy background");

        Graphics2D graphics = cover.createGraphics();
        try {
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 70));
            var wrapped = SeedDataRunner.wrapTitle(
                    "A very long event title that should wrap neatly over no more than three lines",
                    graphics.getFontMetrics(), 540);
            assertTrue(wrapped.size() <= 3);
            assertTrue(wrapped.stream().allMatch(line ->
                    graphics.getFontMetrics().stringWidth(line) <= 540));
        } finally {
            graphics.dispose();
        }
    }
}
