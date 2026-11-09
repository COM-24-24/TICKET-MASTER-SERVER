package com.ticketmaster.ticketmaster.events.entity;

import com.ticketmaster.ticketmaster.common.exception.NotFoundException;
import com.ticketmaster.ticketmaster.events.EventCancelledEvent;
import com.ticketmaster.ticketmaster.events.dto.EventResponse;
import com.ticketmaster.ticketmaster.events.dto.EventResponse.TicketTypeResponse;
import com.ticketmaster.ticketmaster.user.entity.User;
import com.ticketmaster.ticketmaster.user.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class EventService {

    private final ApplicationEventPublisher eventPublisher;
    private final EventRepository eventRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final UserRepository userRepository;

    public EventService(EventRepository eventRepository, ApplicationEventPublisher eventPublisher,
            TicketTypeRepository ticketTypeRepository, UserRepository userRepository) {
        this.eventRepository = eventRepository;
        this.eventPublisher = eventPublisher;
        this.ticketTypeRepository = ticketTypeRepository;
        this.userRepository = userRepository;
    }

    public Event createEvent(Event event, String organizerUsername) {
        if (organizerUsername == null || organizerUsername.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Organizer must be authenticated");
        }
        User organizer = userRepository.findByEmail(organizerUsername)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Authenticated user not found."));
        validateEventTimes(event);
        event.setOrganizerUsername(organizerUsername);
        event.setOrganizerId(organizer.getId());
        event.setStatus(EventStatus.DRAFT);
        return eventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public List<EventResponse> getAllEvents(String category, String search) {
        String normalizedCategory = normalize(category);
        String normalizedSearch = normalize(search);
        return eventRepository.findByStatusInOrderByStartTimeAsc(
                        List.of(EventStatus.PUBLISHED, EventStatus.CANCELLED))
                .stream()
                .filter(event -> normalizedCategory == null
                        || Objects.equals(normalize(event.getCategory() == null
                                ? "Other" : event.getCategory()), normalizedCategory))
                .filter(event -> normalizedSearch == null || matchesSearch(event, normalizedSearch))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public EventResponse getEventResponseById(Long id, String requesterEmail) {
        Event event = getEvent(id);
        if (event.getStatus() == EventStatus.DRAFT && !isOwner(event, requesterEmail)) {
            throw new NotFoundException("Event not found.");
        }
        return toResponse(event);
    }

    public Event editEvent(Long id, Event updatedEvent, String organizerUsername) {
        Event existingEvent = getEvent(id);
        checkOrganizer(existingEvent, organizerUsername);
        if (existingEvent.getStatus() != EventStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only draft events can be edited");
        }
        validateEventTimes(updatedEvent);
        existingEvent.setTitle(updatedEvent.getTitle());
        existingEvent.setCategory(updatedEvent.getCategory());
        existingEvent.setVenue(updatedEvent.getVenue());
        existingEvent.setLocation(updatedEvent.getLocation());
        existingEvent.setDateTime(updatedEvent.getDateTime());
        existingEvent.setStartTime(updatedEvent.getStartTime());
        existingEvent.setEndTime(updatedEvent.getEndTime());
        return eventRepository.save(existingEvent);
    }

    public Event publishEvent(Long id, String organizerUsername) {
        Event event = getEvent(id);
        checkOrganizer(event, organizerUsername);
        if (event.getStatus() != EventStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only draft events can be published");
        }
        validateEventTimes(event);
        event.setStatus(EventStatus.PUBLISHED);
        return eventRepository.save(event);
    }

    public Event cancelEvent(Long id, String organizerUsername) {
        Event event = getEvent(id);
        checkOrganizer(event, organizerUsername);
        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Event is already cancelled");
        }
        boolean wasPublished = (event.getStatus() == EventStatus.PUBLISHED);
        event.setStatus(EventStatus.CANCELLED);
        Event savedEvent = eventRepository.save(event);
        if (wasPublished) {
            eventPublisher.publishEvent(new EventCancelledEvent(this, savedEvent.getId(), savedEvent.getTitle()));
        }
        return savedEvent;
    }

    private Event getEvent(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Event not found."));
    }

    private boolean isOwner(Event event, String requesterEmail) {
        if (requesterEmail == null || requesterEmail.isBlank()) {
            return false;
        }
        if (requesterEmail.equals(event.getOrganizerUsername())) {
            return true;
        }
        return event.getOrganizerId() != null
                && userRepository.findByEmail(requesterEmail)
                        .map(User::getId)
                        .filter(event.getOrganizerId()::equals)
                        .isPresent();
    }

    private EventResponse toResponse(Event event) {
        List<TicketType> ticketTypes = ticketTypeRepository.findByEventId(event.getId());
        long capacity = ticketTypes.stream()
                .mapToLong(type -> type.getTotalQuantity() == null ? 0 : type.getTotalQuantity())
                .sum();
        long remaining = ticketTypes.stream()
                .mapToLong(type -> type.getRemaining() == null ? 0 : type.getRemaining())
                .sum();
        var minPrice = ticketTypes.stream()
                .map(TicketType::getPrice)
                .filter(Objects::nonNull)
                .min(java.math.BigDecimal::compareTo)
                .orElse(null);

        return new EventResponse(
                event.getId(),
                event.getTitle(),
                event.getCategory() == null ? "Other" : event.getCategory(),
                event.getVenue(),
                event.getLocation(),
                event.getStartTime(),
                event.getEndTime(),
                event.getStatus(),
                availability(event, remaining),
                event.getCoverImagePath(),
                minPrice,
                capacity,
                remaining,
                ticketTypes.stream()
                        .map(type -> new TicketTypeResponse(
                                type.getId(), type.getName(), type.getPrice(),
                                type.getTotalQuantity(), type.getRemaining()))
                        .toList());
    }

    private String availability(Event event, long remainingTickets) {
        if (event.getStatus() == EventStatus.CANCELLED) {
            return "CANCELLED";
        }
        if (event.getEndTime() != null && !event.getEndTime().isAfter(LocalDateTime.now())) {
            return "ENDED";
        }
        return remainingTickets <= 0 ? "SOLD_OUT" : "AVAILABLE";
    }

    private boolean matchesSearch(Event event, String search) {
        return contains(event.getTitle(), search)
                || contains(event.getVenue(), search)
                || contains(event.getLocation(), search);
    }

    private boolean contains(String value, String search) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(search);
    }

    private String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim().toLowerCase(Locale.ROOT);
    }

    private void validateEventTimes(Event event) {
        if (event.getStartTime() == null || event.getEndTime() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Event start time and end time are required");
        }
        if (!event.getEndTime().isAfter(event.getStartTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Event end time must be later than start time");
        }
    }

    // TEMPORARY BYPASS FOR DEMO
    private void checkOrganizer(Event event, String organizerUsername) {
        /*
        if (organizerUsername == null
                || !organizerUsername.equals(
                        event.getOrganizerUsername())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only the event organizer can perform this action");
        }
        */
    }
}