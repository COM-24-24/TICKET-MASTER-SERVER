package com.ticketmaster.ticketmaster.user.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class DuplicateEmailException extends ResponseStatusException {

    public DuplicateEmailException() {
        super(HttpStatus.CONFLICT, "An account with this email already exists.");
    }
}
