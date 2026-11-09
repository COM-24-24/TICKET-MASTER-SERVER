package com.ticketmaster.ticketmaster.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class UploadResourceConfig implements WebMvcConfigurer {

    private final String location;

    public UploadResourceConfig(@Value("${app.uploads-dir:uploads}") String uploadsDirectory) {
        String uri = Path.of(uploadsDirectory).toAbsolutePath().normalize().toUri().toString();
        this.location = uri.endsWith("/") ? uri : uri + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);
    }
}
