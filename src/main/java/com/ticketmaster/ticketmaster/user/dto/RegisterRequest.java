package com.ticketmaster.ticketmaster.user.dto;

import com.ticketmaster.ticketmaster.user.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RegisterRequest {

    @Email(message = "Invalid email format")
    @NotBlank(message = "Email is required")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 6, message = "Password must be at least 6 characters")
    private String password;

    @Size(max = 120, message = "Name must not exceed 120 characters")
    private String name;

    private User.Role role = User.Role.CUSTOMER;

    public RegisterRequest() {}

    public RegisterRequest(String email, String password, User.Role role) {
        this.email = email;
        this.password = password;
        this.role = role == null ? User.Role.CUSTOMER : role;
    }

    public RegisterRequest(String email, String password, String name, User.Role role) {
        this(email, password, role);
        this.name = name;
    }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public User.Role getRole() { return role; }
    public void setRole(User.Role role) { this.role = role == null ? User.Role.CUSTOMER : role; }
}