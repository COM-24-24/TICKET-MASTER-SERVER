package com.ticketmaster.ticketmaster.customer;

import com.ticketmaster.ticketmaster.user.entity.User;
import com.ticketmaster.ticketmaster.user.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CustomerProfileService {

    private final CustomerProfileRepository repo;
    private final UserRepository userRepository;

    public CustomerProfileService(CustomerProfileRepository repo, UserRepository userRepository) {
        this.repo = repo;
        this.userRepository = userRepository;
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required.");
        }
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Authenticated user not found."));
    }

    @Transactional
    public CustomerProfileResponse getMyProfile() {
        User user = currentUser();
        Long userId = user.getId();
        CustomerProfile p = repo.findByUserId(userId)
                .orElseGet(() -> {
                    return repo.save(new CustomerProfile(userId, emailPrefix(user.getEmail())));
                });
        return CustomerProfileResponse.from(p, user);
    }

    @Transactional
    public CustomerProfileResponse updateMyProfile(CustomerProfileRequest req) {
        User user = currentUser();
        Long userId = user.getId();
        CustomerProfile p = repo.findByUserId(userId)
                .orElseGet(() -> new CustomerProfile(userId, req.fullName()));

        p.setFullName(req.fullName());
        p.setPhone(req.phone());
        p.setCity(req.city());
        p.setLatitude(req.latitude());
        p.setLongitude(req.longitude());

        return CustomerProfileResponse.from(repo.save(p), user);
    }

    /** Call this from the registration flow once the User module exists. */
    @Transactional
    public CustomerProfile createForUser(Long userId, String fullName) {
        return repo.findByUserId(userId).orElseGet(() ->
                repo.save(new CustomerProfile(userId, fullName)));
    }

    @Transactional(readOnly = true)
    public String displayNameFor(User user) {
        return repo.findByUserId(user.getId())
                .map(CustomerProfile::getFullName)
                .filter(name -> name != null && !name.isBlank())
                .orElseGet(() -> emailPrefix(user.getEmail()));
    }

    private String emailPrefix(String email) {
        int separator = email.indexOf('@');
        return separator > 0 ? email.substring(0, separator) : email;
    }
}
