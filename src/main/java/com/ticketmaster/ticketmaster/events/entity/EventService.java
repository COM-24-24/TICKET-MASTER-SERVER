package com.ticketmaster.ticketmaster.events.entity;

import com.ticketmaster.ticketmaster.events.EventCancelledEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class EventService {

    private final ApplicationEventPublisher eventPublisher;
    private final EventRepository eventRepository;

    public EventService(EventRepository eventRepository, ApplicationEventPublisher eventPublisher) {
        this.eventRepository = eventRepository;
        this.eventPublisher = eventPublisher;
    }

    public Event createEvent(Event event, String organizerUsername) {
        if (organizerUsername == null || organizerUsername.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Organizer must be authenticated");
        }
        validateEventTimes(event);
        event.setOrganizerUsername(organizerUsername);
        event.setStatus(EventStatus.DRAFT);
        return eventRepository.save(event);
    }

    public List<Event> getAllEvents() {
        return eventRepository.findAll();
    }

    public Event getEventById(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
    }

    public Event editEvent(Long id, Event updatedEvent, String organizerUsername) {
        Event existingEvent = getEventById(id);
        checkOrganizer(existingEvent, organizerUsername);
        if (existingEvent.getStatus() != EventStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only draft events can be edited");
        }
        validateEventTimes(updatedEvent);
        existingEvent.setTitle(updatedEvent.getTitle());
        existingEvent.setVenue(updatedEvent.getVenue());
        existingEvent.setLocation(updatedEvent.getLocation());
        existingEvent.setDateTime(updatedEvent.getDateTime());
        existingEvent.setStartTime(updatedEvent.getStartTime());
        existingEvent.setEndTime(updatedEvent.getEndTime());
        return eventRepository.save(existingEvent);
    }

    public Event publishEvent(Long id, String organizerUsername) {
        Event event = getEventById(id);
        checkOrganizer(event, organizerUsername);
        if (event.getStatus() != EventStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only draft events can be published");
        }
        validateEventTimes(event);
        event.setStatus(EventStatus.PUBLISHED);
        return eventRepository.save(event);
    }

    public Event cancelEvent(Long id, String organizerUsername) {
        Event event = getEventById(id);
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