package com.ticketmaster.ticketmaster.order.dto;

import java.math.BigDecimal;

public class OrderItemResponse {
    private Long ticketTypeId;
    private Integer quantity;
    private BigDecimal unitPrice;

    public OrderItemResponse() {}

    public OrderItemResponse(Long ticketTypeId, Integer quantity, BigDecimal unitPrice) {
        this.ticketTypeId = ticketTypeId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public static OrderItemResponseBuilder builder() {
        return new OrderItemResponseBuilder();
    }

    public static class OrderItemResponseBuilder {
        private Long ticketTypeId;
        private Integer quantity;
        private BigDecimal unitPrice;

        public OrderItemResponseBuilder ticketTypeId(Long ticketTypeId) { this.ticketTypeId = ticketTypeId; return this; }
        public OrderItemResponseBuilder quantity(Integer quantity) { this.quantity = quantity; return this; }
        public OrderItemResponseBuilder unitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; return this; }

        public OrderItemResponse build() {
            return new OrderItemResponse(ticketTypeId, quantity, unitPrice);
        }
    }

    public Long getTicketTypeId() { return ticketTypeId; }
    public void setTicketTypeId(Long ticketTypeId) { this.ticketTypeId = ticketTypeId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
}