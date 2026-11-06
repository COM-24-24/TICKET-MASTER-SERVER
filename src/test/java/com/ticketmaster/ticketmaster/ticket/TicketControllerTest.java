package com.ticketmaster.ticketmaster.ticket;

import com.ticketmaster.ticketmaster.ticket.dto.TicketResponse;
import com.ticketmaster.ticketmaster.user.entity.User;
import com.ticketmaster.ticketmaster.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketControllerTest {

    @Mock
    private TicketQueryService ticketQueryService;

    @Mock
    private TicketCancellationService ticketCancellationService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private TicketController ticketController;

    @Test
    void getMyTicketsUsesAuthenticatedUsersDatabaseId() {
        User user = User.builder()
                .id(7L)
                .email("customer@example.com")
                .password("encoded-password")
                .role(User.Role.CUSTOMER)
                .build();

        when(authentication.getName()).thenReturn("customer@example.com");
        when(userRepository.findByEmail("customer@example.com"))

 .thenReturn(Optional.of(user));
        when(ticketQueryService.getMyTicketById(10L, 7L))
                .thenThrow(new ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN
                ));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> ticketController.getTicketById(10L, authentication)
        );

        assertEquals(403, exception.getStatusCode().value());
    }
}
