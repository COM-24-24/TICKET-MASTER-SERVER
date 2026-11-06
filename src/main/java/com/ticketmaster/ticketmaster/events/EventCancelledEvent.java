package com.ticketmaster.ticketmaster.events;

import org.springframework.context.ApplicationEvent;

public class EventCancelledEvent extends ApplicationEvent {
    
    private final Long eventId;
    private final String eventTitle;

    public EventCancelledEvent(Object source, Long eventId, String eventTitle) {
        super(source);
        this.eventId = eventId;
        this.eventTitle = eventTitle;
    }

    public Long getEventId() { return eventId; }
    public String getEventTitle() { return eventTitle; }
}