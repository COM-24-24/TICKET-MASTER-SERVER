package com.ticketmaster.ticketmaster.ticket;

import com.ticketmaster.ticketmaster.common.exception.NotFoundException;
import com.ticketmaster.ticketmaster.customer.CustomerProfileRepository;
import com.ticketmaster.ticketmaster.entity.Payment;
import com.ticketmaster.ticketmaster.entity.PaymentStatus;
import com.ticketmaster.ticketmaster.order.entity.Order;
import com.ticketmaster.ticketmaster.order.repository.OrderRepository;
import com.ticketmaster.ticketmaster.repository.PaymentRepository;
import com.ticketmaster.ticketmaster.ticket.dto.TicketResponse;
import com.ticketmaster.ticketmaster.user.entity.User;
import com.ticketmaster.ticketmaster.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class TicketQueryService {

    private final TicketRepository ticketRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final PaymentRepository paymentRepository;

    public TicketQueryService(
            TicketRepository ticketRepository,
            OrderRepository orderRepository,
            UserRepository userRepository,
            CustomerProfileRepository customerProfileRepository,
            PaymentRepository paymentRepository) {
        this.ticketRepository = ticketRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.customerProfileRepository = customerProfileRepository;
        this.paymentRepository = paymentRepository;
    }

    @Transactional(readOnly = true)
    public List<TicketResponse> getMyTickets(Long ownerId) {
        return ticketRepository.findByOwnerIdOrderByIssuedAtDesc(ownerId)
                .stream()
                .map(ticket -> toIssuedPaidResponse(ticket, ownerId))
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }

    @Transactional(readOnly = true)
    public TicketResponse getMyTicketById(Long ticketId, Long ownerId) {
        return ticketRepository.findByIdAndOwnerId(ticketId, ownerId)
                .flatMap(ticket -> toIssuedPaidResponse(ticket, ownerId))
                .orElseThrow(() -> new NotFoundException("Ticket not found."));
    }

    @Transactional(readOnly = true)
    public List<TicketResponse> getOrderTickets(Long orderId, Long ownerId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found."));
        if (!order.getUser().getId().equals(ownerId)) {
            throw new NotFoundException("Order not found.");
        }
        return ticketRepository.findAllByOrderId(orderId)
                .stream()
                .map(ticket -> toIssuedPaidResponse(ticket, ownerId))
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }

    private Optional<TicketResponse> toIssuedPaidResponse(Ticket ticket, Long ownerId) {
        if (!ownerId.equals(ticket.getOwnerId())
                || (ticket.getStatus() != TicketStatus.VALID && ticket.getStatus() != TicketStatus.USED)) {
            return Optional.empty();
        }

        Order order = orderRepository.findById(ticket.getOrderId()).orElse(null);
        if (order == null || order.getStatus() != Order.OrderStatus.PAID
                || order.getUser() == null || !ownerId.equals(order.getUser().getId())) {
            return Optional.empty();
        }

        Payment payment = paymentRepository.findByOrderIdOrderByCreatedAtDesc(ticket.getOrderId())
                .stream()
                .findFirst()
                .filter(attempt -> attempt.getStatus() == PaymentStatus.PAID)
                .orElse(null);
        if (payment == null) {
            return Optional.empty();
        }

        User buyer = userRepository.findById(ticket.getOwnerId())
                .orElseThrow(() -> new IllegalStateException("Ticket buyer was not found."));
        String buyerName = customerProfileRepository.findByUserId(buyer.getId())
                .map(profile -> profile.getFullName())
                .filter(name -> name != null && !name.isBlank())
                .orElseGet(() -> emailPrefix(buyer.getEmail()));
        TicketResponse response = new TicketResponse(
                ticket.getId(),
                ticket.getOwnerId(),
                ticket.getOrderId(),
                ticket.getTicketCode(),
                ticket.getEvent().getId(),
                ticket.getEvent().getTitle(),
                ticket.getEvent().getVenue(),
                ticket.getEvent().getLocation(),
                ticket.getEvent().getDateTime(),
                ticket.getEvent().getStartTime(),
                ticket.getEvent().getEndTime(),
                ticket.getTicketType().getId(),
                ticket.getTicketType().getName(),
                ticket.getTicketType().getPrice(),
                ticket.getStatus(),
                payment.getStatus().name(),
                ticket.getIssuedAt(),
                ticket.getUsedAt(),
                ticket.getTicketCode(),
                buyerName,
                buyer.getEmail(),
                payment.getTxRef(),
                "MWK",
                ticket.getIssuedAt(),
                ticket.getEvent().getCoverImagePath());
        return Optional.of(response);
    }

    private String emailPrefix(String email) {
        int separator = email.indexOf('@');
        return separator < 0 ? email : email.substring(0, separator);
    }
}
