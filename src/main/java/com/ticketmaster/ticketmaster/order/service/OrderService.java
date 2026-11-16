package com.ticketmaster.ticketmaster.order.service;

import com.ticketmaster.ticketmaster.common.exception.NotFoundException;
import com.ticketmaster.ticketmaster.events.entity.Event;
import com.ticketmaster.ticketmaster.events.entity.EventRepository;
import com.ticketmaster.ticketmaster.events.entity.EventStatus;
import com.ticketmaster.ticketmaster.events.entity.TicketType;
import com.ticketmaster.ticketmaster.events.entity.TicketTypeRepository;
import com.ticketmaster.ticketmaster.order.dto.OrderItemResponse;
import com.ticketmaster.ticketmaster.order.dto.OrderResponse;
import com.ticketmaster.ticketmaster.order.dto.PlaceOrderRequest;
import com.ticketmaster.ticketmaster.order.entity.Order;
import com.ticketmaster.ticketmaster.order.entity.OrderItem;
import com.ticketmaster.ticketmaster.order.repository.OrderRepository;
import com.ticketmaster.ticketmaster.ticket.TicketIssuingService;
import com.ticketmaster.ticketmaster.user.entity.User;
import com.ticketmaster.ticketmaster.user.repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final TicketIssuingService ticketIssuingService;
    private final int maxTicketsPerOrder;
    private final int pendingMinutes;

    public OrderService(OrderRepository orderRepository, UserRepository userRepository,
            EventRepository eventRepository, TicketTypeRepository ticketTypeRepository,
            TicketIssuingService ticketIssuingService,
            @Value("${app.orders.max-tickets-per-order:10}") int maxTicketsPerOrder,
            @Value("${app.orders.pending-minutes:30}") int pendingMinutes) {
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.ticketIssuingService = ticketIssuingService;
        this.maxTicketsPerOrder = maxTicketsPerOrder;
        this.pendingMinutes = pendingMinutes;
    }

    @Transactional
    public OrderResponse placeOrder(PlaceOrderRequest request) {
        if (request.getEventId() == null) {
            throw new IllegalArgumentException("Event ID cannot be null");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one item");
        }
        Event event = eventRepository.findById(request.getEventId())
                .orElseThrow(() -> new NotFoundException("Event not found."));
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new IllegalArgumentException("Tickets can only be ordered for published events.");
        }
        if (event.getEndTime() == null || !event.getEndTime().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Tickets cannot be ordered for an event that has ended.");
        }

        int totalQuantity = 0;
        Set<Long> ticketTypeIds = new HashSet<>();
        for (PlaceOrderRequest.ItemRequest item : request.getItems()) {
            if (!ticketTypeIds.add(item.getTicketTypeId())) {
                throw new IllegalArgumentException("An order cannot contain duplicate ticket types.");
            }
            totalQuantity += item.getQuantity();
        }
        if (totalQuantity > maxTicketsPerOrder) {
            throw new IllegalArgumentException(
                    "An order cannot contain more than " + maxTicketsPerOrder + " tickets.");
        }

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Order order = Order.builder()
                .user(user)
                .eventId(request.getEventId())
                .status(Order.OrderStatus.PENDING)
                .totalAmount(BigDecimal.ZERO)
                .build();

        BigDecimal totalAmount = BigDecimal.ZERO;

        for (PlaceOrderRequest.ItemRequest itemReq : request.getItems()) {
            
            TicketType ticketType = ticketTypeRepository.findByIdForUpdate(itemReq.getTicketTypeId())
                    .orElseThrow(() -> new NotFoundException("Ticket type not found."));
            if (!ticketType.getEvent().getId().equals(request.getEventId())) {
                throw new IllegalArgumentException(
                        "Ticket type " + itemReq.getTicketTypeId() + " does not belong to event "
                                + request.getEventId() + ".");
            }

            int available = ticketType.getTotalQuantity() - ticketType.getQuantitySold();
            if (available < itemReq.getQuantity()) {
                throw new IllegalArgumentException("Not enough tickets available for: " + ticketType.getName());
            }

            ticketType.setQuantitySold(ticketType.getQuantitySold() + itemReq.getQuantity());
            ticketTypeRepository.save(ticketType);

            BigDecimal unitPrice = ticketType.getPrice();

            OrderItem orderItem = OrderItem.builder()
                    .order(order)
                    .ticketTypeId(itemReq.getTicketTypeId())
                    .quantity(itemReq.getQuantity())
                    .unitPrice(unitPrice)
                    .build();

            order.getItems().add(orderItem);

            BigDecimal itemTotal = unitPrice.multiply(new BigDecimal(itemReq.getQuantity()));
            totalAmount = totalAmount.add(itemTotal);
        }

        order.setTotalAmount(totalAmount);
        Order savedOrder = orderRepository.save(order);

        return mapToResponse(savedOrder);
    }

    private OrderResponse mapToResponse(Order order) {
        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> OrderItemResponse.builder()
                        .ticketTypeId(item.getTicketTypeId())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .build())
                .collect(Collectors.toList());

        return OrderResponse.builder()
                .id(order.getId())
                .eventId(order.getEventId())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus().name())
                .createdAt(order.getCreatedAt())
                .items(itemResponses)
                .build();
    }

    public List<OrderResponse> getMyOrders() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        return orderRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public OrderResponse getOrderById(Long orderId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found."));

        if (!order.getUser().getEmail().equals(email)) {
            throw new NotFoundException("Order not found.");
        }

        return mapToResponse(order);
    }

    @Transactional
    public OrderResponse getOrderForCheckout(Long orderId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found."));
        if (!order.getUser().getEmail().equals(email)) {
            throw new AccessDeniedException("You are not authorized to view this order.");
        }
        return mapToResponse(order);
    }

    @Transactional
    public void updateOrderStatus(Long orderId, Order.OrderStatus newStatus) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found."));

        if (order.getStatus() == newStatus) {
            return;
        }

        if (order.getStatus() == Order.OrderStatus.PAID || order.getStatus() == Order.OrderStatus.CANCELLED) {
            throw new IllegalStateException("Cannot change status of a " + order.getStatus() + " order.");
        }

        if (newStatus == Order.OrderStatus.PAID) {
            throw new IllegalArgumentException("Use markOrderPaid to mark an order PAID.");
        }
        if (newStatus == Order.OrderStatus.FAILED || newStatus == Order.OrderStatus.CANCELLED) {
            releaseReservedStock(order);
            order.setStatus(newStatus);
        }

        orderRepository.save(order);
    }

    @Transactional
    public void markOrderPaid(Long orderId, String source) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found."));
        if (order.getStatus() == Order.OrderStatus.PAID) {
            return;
        }
        if (order.getStatus() != Order.OrderStatus.PENDING) {
            throw new IllegalStateException("Only PENDING orders can be marked PAID.");
        }
        order.setStatus(Order.OrderStatus.PAID);
        orderRepository.save(order);
        ticketIssuingService.issueTickets(order);
    }

    @Transactional
    @Scheduled(fixedRate = 60_000)
    public void expirePendingOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(pendingMinutes);
        List<Order> expiredOrders = orderRepository.findByStatusAndCreatedAtBefore(
                Order.OrderStatus.PENDING, cutoff);
        for (Order staleOrder : expiredOrders) {
            Order order = orderRepository.findByIdForUpdate(staleOrder.getId())
                    .orElseThrow(() -> new NotFoundException("Order not found."));
            if (order.getStatus() == Order.OrderStatus.PENDING
                    && order.getCreatedAt().isBefore(cutoff)) {
                releaseReservedStock(order);
                order.setStatus(Order.OrderStatus.CANCELLED);
                orderRepository.save(order);
            }
        }
    }

    private void releaseReservedStock(Order order) {
        for (OrderItem item : order.getItems()) {
            TicketType ticketType = ticketTypeRepository.findByIdForUpdate(item.getTicketTypeId())
                    .orElseThrow(() -> new NotFoundException("Ticket type not found."));
            ticketType.setQuantitySold(Math.max(0, ticketType.getQuantitySold() - item.getQuantity()));
            ticketTypeRepository.save(ticketType);
        }
    }

    @Transactional
    public OrderResponse cancelOrder(Long orderId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found."));

        if (!order.getUser().getEmail().equals(email)) {
            throw new NotFoundException("Order not found.");
        }

        if (order.getStatus() != Order.OrderStatus.PENDING) {
            throw new IllegalStateException("Only PENDING orders can be cancelled");
        }

        releaseReservedStock(order);
        order.setStatus(Order.OrderStatus.CANCELLED);
        orderRepository.save(order);

        return mapToResponse(order);
    }
}
