package com.ticketmaster.ticketmaster.events;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    @EventListener
    public void handleEventCancelled(EventCancelledEvent event) {
        // In a real system, you would query Zymal's OrderRepository here
        // to find all PAID orders for this event and email those customers.
        
        System.out.println("\n=======================================================");
        System.out.println("🔔 NOTIFICATION SENT TO ALL TICKET HOLDERS");
        System.out.println("=======================================================");
        System.out.println("Event ID: " + event.getEventId());
        System.out.println("Message: The event '" + event.getEventTitle() + "' has been CANCELLED.");
        System.out.println("Action: Please check your email for refund information.");
        System.out.println("=======================================================\n");
    }
}