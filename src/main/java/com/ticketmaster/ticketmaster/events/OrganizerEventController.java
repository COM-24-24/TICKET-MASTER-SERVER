package com.ticketmaster.ticketmaster.events;

import com.ticketmaster.ticketmaster.events.dto.EventImageResponse;
import com.ticketmaster.ticketmaster.events.service.EventImageService;
import com.ticketmaster.ticketmaster.events.entity.Event;
import com.ticketmaster.ticketmaster.events.entity.EventRepository;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/organizer/events")
public class OrganizerEventController {

    private final EventRepository eventRepository;
    private final EventImageService eventImageService;

    public OrganizerEventController(EventRepository eventRepository, EventImageService eventImageService) {
        this.eventRepository = eventRepository;
        this.eventImageService = eventImageService;
    }

    @GetMapping
    @Operation(summary = "List events owned by the authenticated organizer")
    public List<Event> myEvents(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in first");
        }
        return eventRepository.findByOrganizerUsername(auth.getName());
    }

    @PostMapping(value = "/{eventId}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a JPG or PNG cover image for an owned event")
    public EventImageResponse uploadImage(
            @PathVariable Long eventId,
            @RequestPart("file") MultipartFile file,
            Authentication auth) {
        return eventImageService.upload(eventId, file, auth.getName());
    }
}