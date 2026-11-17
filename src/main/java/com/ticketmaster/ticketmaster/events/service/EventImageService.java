package com.ticketmaster.ticketmaster.events.service;

import com.ticketmaster.ticketmaster.common.exception.NotFoundException;
import com.ticketmaster.ticketmaster.events.dto.EventImageResponse;
import com.ticketmaster.ticketmaster.events.entity.Event;
import com.ticketmaster.ticketmaster.events.entity.EventRepository;
import com.ticketmaster.ticketmaster.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.nio.file.InvalidPathException;

@Service
public class EventImageService {

    private static final long MAX_IMAGE_SIZE = 2L * 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final Path uploadsDirectory;

    public EventImageService(
            EventRepository eventRepository,
            UserRepository userRepository,
            @Value("${app.uploads-dir:uploads}") String uploadsDirectory) {
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.uploadsDirectory = Path.of(uploadsDirectory).toAbsolutePath().normalize();
    }

    @Transactional
    public EventImageResponse upload(Long eventId, MultipartFile file, String organizerEmail) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found."));
        boolean ownsEvent = organizerEmail != null
                && (organizerEmail.equals(event.getOrganizerUsername())
                        || userRepository.findByEmail(organizerEmail)
                                .map(user -> user.getId().equals(event.getOrganizerId()))
                                .orElse(false));
        if (!ownsEvent) {
            throw new AccessDeniedException("Only the event organizer can upload an image.");
        }

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("An image file is required.");
        }
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new IllegalArgumentException("Image must not exceed 2 MB.");
        }

        try {
            byte[] content = file.getBytes();
            String extension = imageExtension(content);
            Path eventDirectory = uploadsDirectory.resolve("events");
            Files.createDirectories(eventDirectory);
            String filename = UUID.randomUUID() + extension;
            Path destination = eventDirectory.resolve(filename).normalize();
            if (!destination.startsWith(eventDirectory)) {
                throw new IllegalArgumentException("Invalid image path.");
            }
            Files.write(destination, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

            String previousPath = event.getCoverImagePath();
            String relativePath = "/uploads/events/" + filename;
            event.setCoverImagePath(relativePath);
            eventRepository.saveAndFlush(event);
            deletePreviousImage(previousPath, eventDirectory, destination);
            return new EventImageResponse(relativePath);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to store event image.", exception);
        }
    }

    private void deletePreviousImage(String previousPath, Path eventDirectory, Path replacement) {
        if (previousPath == null || !previousPath.startsWith("/uploads/events/")) return;
        try {
            Path previous = eventDirectory.resolve(previousPath.substring("/uploads/events/".length()))
                    .normalize();
            if (previous.startsWith(eventDirectory) && !previous.equals(replacement)) {
                Files.deleteIfExists(previous);
            }
        } catch (InvalidPathException | IOException ignored) {
            // The new image is already saved; an obsolete file can be cleaned up separately.
        }
    }

    private String imageExtension(byte[] content) {
        if (hasPrefix(content, PNG_SIGNATURE)) {
            return ".png";
        }
        if (content.length >= 3
                && content[0] == (byte) 0xff
                && content[1] == (byte) 0xd8
                && content[2] == (byte) 0xff) {
            return ".jpg";
        }
        throw new IllegalArgumentException("Only JPG and PNG images are supported.");
    }

    private boolean hasPrefix(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }
}
