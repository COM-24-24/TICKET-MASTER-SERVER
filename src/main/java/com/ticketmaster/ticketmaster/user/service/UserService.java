package com.ticketmaster.ticketmaster.user.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.ticketmaster.ticketmaster.common.event.UserRegisteredEvent;
import com.ticketmaster.ticketmaster.customer.CustomerProfileService;
import com.ticketmaster.ticketmaster.security.CustomUserDetailsService;
import com.ticketmaster.ticketmaster.security.JwtService;
import com.ticketmaster.ticketmaster.user.dto.AuthResponse;
import com.ticketmaster.ticketmaster.user.dto.AuthUserResponse;
import com.ticketmaster.ticketmaster.user.dto.LoginRequest;
import com.ticketmaster.ticketmaster.user.dto.RegisterRequest;
import com.ticketmaster.ticketmaster.user.entity.User;
import com.ticketmaster.ticketmaster.user.exception.DuplicateEmailException;
import com.ticketmaster.ticketmaster.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService userDetailsService;
    private final ApplicationEventPublisher eventPublisher;
    private final CustomerProfileService customerProfileService;

    @Value("${google.client-id}")
    private String googleClientId;

    @Value("${app.registration.allow-privileged-roles:false}")
    private boolean allowPrivilegedRoles;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                       AuthenticationManager authenticationManager, CustomUserDetailsService userDetailsService,
                       ApplicationEventPublisher eventPublisher, CustomerProfileService customerProfileService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.eventPublisher = eventPublisher;
        this.customerProfileService = customerProfileService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        User.Role role = request.getRole() == null ? User.Role.CUSTOMER : request.getRole();
        if (role != User.Role.CUSTOMER && !allowPrivilegedRoles) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only customer accounts can be created here.");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateEmailException();
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(role)
                .build();

        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException exception) {
            if (userRepository.existsByEmail(request.getEmail())) {
                throw new DuplicateEmailException();
            }
            throw exception;
        }
        if (role == User.Role.CUSTOMER) {
            customerProfileService.createForUser(user.getId(),
                    preferredName(request.getName(), user.getEmail()));
        }
        eventPublisher.publishEvent(new UserRegisteredEvent(user));

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String token = jwtService.generateToken(userDetails);
        String refreshToken = jwtService.generateRefreshToken(userDetails);

        return AuthResponse.builder()
                .token(token)
                .refreshToken(refreshToken)
                .user(userResponse(user))
                .build();
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String token = jwtService.generateToken(userDetails);
        String refreshToken = jwtService.generateRefreshToken(userDetails);

        return AuthResponse.builder()
                .token(token)
                .refreshToken(refreshToken)
                .user(userResponse(user))
                .build();
    }

    public AuthResponse googleLogin(String idTokenString) {
        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), new GsonFactory())
                    .setAudience(Collections.singletonList(googleClientId))
                    .build();

            GoogleIdToken idToken = verifier.verify(idTokenString);
            if (idToken == null) {
                throw new IllegalArgumentException("Invalid Google ID token.");
            }

            GoogleIdToken.Payload payload = idToken.getPayload();
            String email = payload.getEmail();
            String googleName = (String) payload.get("name");

            User user = userRepository.findByEmail(email).orElse(null);

            if (user == null) {
                user = User.builder()
                        .email(email)
                        .password(passwordEncoder.encode(java.util.UUID.randomUUID().toString()))
                        .role(User.Role.CUSTOMER)
                        .build();
                user = userRepository.save(user);
                customerProfileService.createForUser(user.getId(), preferredName(googleName, email));
                eventPublisher.publishEvent(new UserRegisteredEvent(user));
            }

            UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
            String token = jwtService.generateToken(userDetails);
            String refreshToken = jwtService.generateRefreshToken(userDetails);

            return AuthResponse.builder()
                    .token(token)
                    .refreshToken(refreshToken)
                    .user(userResponse(user))
                    .build();

        } catch (Exception e) {
            throw new IllegalArgumentException("Google authentication failed: " + e.getMessage());
        }
    }

    public AuthResponse refreshToken(String refreshToken) {
        String email = jwtService.extractUsername(refreshToken);
        if (email != null) {
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new IllegalArgumentException("Invalid or expired refresh token"));
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);
            if (jwtService.isTokenValid(refreshToken, userDetails)) {
                return AuthResponse.builder()
                        .token(jwtService.generateToken(userDetails))
                        .refreshToken(refreshToken)
                        .user(userResponse(user))
                        .build();
            }
        }
        throw new IllegalArgumentException("Invalid or expired refresh token");
    }

    private AuthUserResponse userResponse(User user) {
        return new AuthUserResponse(user.getId(), customerProfileService.displayNameFor(user),
                user.getEmail(), user.getRole());
    }

    private String preferredName(String name, String email) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        int separator = email.indexOf('@');
        return separator > 0 ? email.substring(0, separator) : email;
    }
}