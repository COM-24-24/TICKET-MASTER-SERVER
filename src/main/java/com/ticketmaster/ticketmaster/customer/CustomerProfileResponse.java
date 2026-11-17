package com.ticketmaster.ticketmaster.customer;

import com.ticketmaster.ticketmaster.user.entity.User;

public record CustomerProfileResponse(
        Long id,
        Long userId,
        String fullName,
        String phone,
        String city,
        Double latitude,
        Double longitude,
        String email,
        String name,
        User.Role role
) {
    public static CustomerProfileResponse from(CustomerProfile p, User user) {
        String profileName = p.getFullName();
        String name = profileName == null || profileName.isBlank()
                ? emailPrefix(user.getEmail()) : profileName;
        return new CustomerProfileResponse(
                p.getId(), p.getUserId(), p.getFullName(), p.getPhone(),
                p.getCity(), p.getLatitude(), p.getLongitude(),
                user.getEmail(), name, user.getRole());
    }

    private static String emailPrefix(String email) {
        int separator = email.indexOf('@');
        return separator > 0 ? email.substring(0, separator) : email;
    }
}
