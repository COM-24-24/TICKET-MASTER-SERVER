package com.ticketmaster.ticketmaster.service;

import com.ticketmaster.ticketmaster.dto.CreatePaymentRequest;
import com.ticketmaster.ticketmaster.entity.Payment;
import com.ticketmaster.ticketmaster.entity.PaymentStatus;
import com.ticketmaster.ticketmaster.order.entity.Order;
import com.ticketmaster.ticketmaster.order.service.OrderService;
import com.ticketmaster.ticketmaster.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class PaymentService {
    private final PaymentRepository paymentRepository;
    private final OrderService orderService;

    public PaymentService(PaymentRepository paymentRepository, OrderService orderService) {
        this.paymentRepository = paymentRepository;
        this.orderService = orderService;
    }

    public Payment createPayment(CreatePaymentRequest request) {
        Payment payment = new Payment();
        payment.setOrderId(request.orderId());
        payment.setAmount(request.amount());
        payment.setStatus(request.shouldSucceed() ? PaymentStatus.PAID : PaymentStatus.FAILED);
        payment.setCreatedAt(LocalDateTime.now());
        payment = paymentRepository.save(payment);

        if (payment.getStatus() == PaymentStatus.PAID) {
            orderService.markOrderPaid(payment.getOrderId(), "legacy-payment-simulation");
            payment.setOrderStatusUpdated(true);
        } else {
            orderService.updateOrderStatus(payment.getOrderId(), Order.OrderStatus.FAILED);
            payment.setOrderStatusUpdated(true);
        }
        return paymentRepository.save(payment);
    }

    public Optional<Payment> getLatestPayment(Long orderId) {
        List<Payment> payments = paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
        return payments.stream().findFirst();
    }
}