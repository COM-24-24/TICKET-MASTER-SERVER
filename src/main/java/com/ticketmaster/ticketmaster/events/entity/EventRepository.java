
package com.ticketmaster.ticketmaster.events.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {
    List<Event> findByOrganizerUsername(String organizerUsername);
    List<Event> findByStatusInOrderByStartTimeAsc(List<EventStatus> statuses);
    Optional<Event> findFirstByTitle(String title);
}