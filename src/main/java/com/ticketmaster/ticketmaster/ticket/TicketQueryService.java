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
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public TicketResponse getMyTicketById(Long ticketId, Long ownerId) {
        return ticketRepository.findByIdAndOwnerId(ticketId, ownerId)
                .map(this::toResponse)
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
                .map(this::toResponse)
                .toList();
    }

    private TicketResponse toResponse(Ticket ticket) {
        User buyer = userRepository.findById(ticket.getOwnerId())
                .orElseThrow(() -> new IllegalStateException("Ticket buyer was not found."));
        String buyerName = customerProfileRepository.findByUserId(buyer.getId())
                .map(profile -> profile.getFullName())
                .filter(name -> name != null && !name.isBlank())
                .orElseGet(() -> emailPrefix(buyer.getEmail()));
        String paymentReference = paymentRepository
                .findByOrderIdOrderByCreatedAtDesc(ticket.getOrderId())
                .stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.PAID)
                .map(Payment::getTxRef)
                .filter(reference -> reference != null && !reference.isBlank())
                .findFirst()
                .orElse(null);

        return new TicketResponse(
                ticket.getId(),
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
                ticket.getIssuedAt(),
                ticket.getUsedAt(),
                ticket.getTicketCode(),
                buyerName,
                buyer.getEmail(),
                paymentReference,
                "MWK",
                ticket.getIssuedAt(),
                ticket.getEvent().getCoverImagePath());
    }

    private String emailPrefix(String email) {
        int separator = email.indexOf('@');
        return separator < 0 ? email : email.substring(0, separator);
    }
}
