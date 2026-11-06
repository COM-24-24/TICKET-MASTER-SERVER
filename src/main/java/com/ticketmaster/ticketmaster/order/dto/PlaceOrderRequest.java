package com.ticketmaster.ticketmaster.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public class PlaceOrderRequest {

    @NotNull(message = "Event ID is required")
    private Long eventId;

    @NotEmpty(message = "Order must contain at least one item")
    @Valid
    private List<ItemRequest> items;

    public PlaceOrderRequest() {}

    public PlaceOrderRequest(Long eventId, List<ItemRequest> items) {
        this.eventId = eventId;
        this.items = items;
    }

    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public List<ItemRequest> getItems() { return items; }
    public void setItems(List<ItemRequest> items) { this.items = items; }

    public static class ItemRequest {
        @NotNull(message = "Ticket type ID is required")
        private Long ticketTypeId;

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        private Integer quantity;

        public ItemRequest() {}

        public ItemRequest(Long ticketTypeId, Integer quantity) {
            this.ticketTypeId = ticketTypeId;
            this.quantity = quantity;
        }

        public Long getTicketTypeId() { return ticketTypeId; }
        public void setTicketTypeId(Long ticketTypeId) { this.ticketTypeId = ticketTypeId; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
    }
}