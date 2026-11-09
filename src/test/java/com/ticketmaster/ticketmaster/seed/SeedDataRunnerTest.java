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
    void runningTwiceDoesNotDuplicateSeedData() {
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
    }
}
