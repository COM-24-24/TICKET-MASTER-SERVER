package com.ticketmaster.ticketmaster.user.dto;

import com.ticketmaster.ticketmaster.user.entity.User;

public record AuthUserResponse(Long id, String name, String email, User.Role role) {
}
