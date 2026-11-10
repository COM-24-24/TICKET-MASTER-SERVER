package com.ticketmaster.ticketmaster.payment;

import com.ticketmaster.ticketmaster.common.exception.NotFoundException;
import com.ticketmaster.ticketmaster.customer.CustomerProfileRepository;
import com.ticketmaster.ticketmaster.entity.Payment;
import com.ticketmaster.ticketmaster.entity.PaymentProvider;
import com.ticketmaster.ticketmaster.entity.PaymentStatus;
import com.ticketmaster.ticketmaster.order.dto.OrderResponse;
import com.ticketmaster.ticketmaster.order.service.OrderService;
import com.ticketmaster.ticketmaster.repository.PaymentRepository;
import com.ticketmaster.ticketmaster.ticket.TicketQueryService;
import com.ticketmaster.ticketmaster.ticket.dto.TicketResponse;
import com.ticketmaster.ticketmaster.user.entity.User;
import com.ticketmaster.ticketmaster.user.repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentProcessingService {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final OrderService orderService;
    private final TicketQueryService ticketQueryService;
    private final PayChanguService payChanguService;
    private final Environment environment;
    private final boolean demoPaymentEnabled;
    private final String demoPaymentBaseUrl;

    public PaymentProcessingService(
            PaymentRepository paymentRepository,
            UserRepository userRepository,
            CustomerProfileRepository customerProfileRepository,
            OrderService orderService,
            TicketQueryService ticketQueryService,
            PayChanguService payChanguService,
            Environment environment,
            @Value("${app.demo-payment.enabled:false}") boolean demoPaymentEnabled,
            @Value("${app.demo-payment.base-url:http://localhost:8080}") String demoPaymentBaseUrl) {
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.customerProfileRepository = customerProfileRepository;
        this.orderService = orderService;
        this.ticketQueryService = ticketQueryService;
        this.payChanguService = payChanguService;
        this.environment = environment;
        this.demoPaymentEnabled = demoPaymentEnabled;
        this.demoPaymentBaseUrl = demoPaymentBaseUrl;
    }

    @Transactional(noRollbackFor = PaymentGatewayException.class)
    public CheckoutResult checkout(Long orderId) {
        OrderResponse order = orderService.getOrderForCheckout(orderId);
        if (!"PENDING".equals(order.getStatus())) {
            throw new IllegalStateException("Only PENDING orders can be checked out.");
        }
        boolean demoMode = isDemoMode();
        boolean activeAttempt = paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .anyMatch(payment -> (payment.getProvider() == PaymentProvider.PAYCHANGU
                        || (demoMode && payment.getProvider() == PaymentProvider.DEMO))
                        && payment.getStatus() == PaymentStatus.PENDING);
        if (activeAttempt) {
            throw new IllegalStateException(
                    "A payment attempt is already pending for this order. Verify its status before retrying.");
        }

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new NotFoundException("User not found."));

        String txRef = UUID.randomUUID().toString();
        if (demoMode) {
            Payment payment = new Payment();
            payment.setOrderId(orderId);
            payment.setAmount(order.getTotalAmount().doubleValue());
            payment.setTxRef(txRef);
            payment.setProvider(PaymentProvider.DEMO);
            payment.setStatus(PaymentStatus.PENDING);
            payment.setCreatedAt(LocalDateTime.now());
            paymentRepository.save(payment);
            String baseUrl = demoPaymentBaseUrl.replaceAll("/+$", "");
            return new CheckoutResult(order.getTotalAmount(), txRef,
                    baseUrl + "/api/dev/payments/checkout/" + txRef);
        }

        String fullName = customerProfileRepository.findByUserId(user.getId())
                .map(profile -> profile.getFullName())
                .filter(name -> name != null && !name.isBlank())
                .orElse("Customer");
        String[] names = splitName(fullName);

        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setAmount(order.getTotalAmount().doubleValue());
        payment.setTxRef(txRef);
        payment.setProvider(PaymentProvider.PAYCHANGU);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setCreatedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        String checkoutUrl;
        try {
            checkoutUrl = payChanguService.initiateCheckout(
                    order.getTotalAmount(), email, names[0], names[1], orderId, txRef);
        } catch (PaymentGatewayException exception) {
            if (exception.isDefinitiveRejection()) {
                payment.setStatus(PaymentStatus.FAILED);
                paymentRepository.save(payment);
            }
            throw exception;
        }
        return new CheckoutResult(order.getTotalAmount(), txRef, checkoutUrl);
    }

    @Transactional
    public PaidOrderResult verifyOrder(Long orderId) {
        OrderResponse order = orderService.getOrderById(orderId);
        if ("PAID".equals(order.getStatus())) {
            return paidOrder(order, "PAID");
        }
        List<Payment> attempts = paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
        if (attempts.isEmpty()) {
            throw new NotFoundException("Payment not found.");
        }

        String result = attempts.get(0).getStatus().name();
        if (attempts.get(0).getStatus() == PaymentStatus.PAID) {
            return paidOrder(order, "PAID");
        }
        for (Payment attempt : attempts) {
            if (attempt.getStatus() != PaymentStatus.PENDING
                    || attempt.getProvider() != PaymentProvider.PAYCHANGU
                    || attempt.getTxRef() == null) {
                continue;
            }
            result = verifyTxRef(attempt.getTxRef()).toUpperCase();
            if ("SUCCESS".equals(result) || "PENDING".equals(result)) {
                break;
            }
        }

        OrderResponse updatedOrder = orderService.getOrderById(orderId);
        return paidOrder(updatedOrder, "PAID".equals(updatedOrder.getStatus()) ? "PAID" : result);
    }

    @Transactional
    public String verifyTxRef(String txRef) {
        Payment payment = paymentRepository.findByTxRefForUpdate(txRef)
                .orElseThrow(() -> new NotFoundException("Payment not found."));
        if (payment.getStatus() == PaymentStatus.PAID) {
            return "success";
        }
        if (payment.getProvider() != PaymentProvider.PAYCHANGU
                || payment.getStatus() != PaymentStatus.PENDING) {
            return payment.getStatus().name().toLowerCase();
        }

        PayChanguService.Verification verification = payChanguService.verify(txRef);
        if (!verification.txRef().equals(payment.getTxRef())) {
            throw new IllegalStateException("Payment verification did not match this order.");
        }
        if ("failed".equalsIgnoreCase(verification.status())) {
            payment.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);
            return "failed";
        }
        if (!"success".equalsIgnoreCase(verification.status())) {
            return "pending";
        }
        BigDecimal orderTotal = BigDecimal.valueOf(payment.getAmount());
        if (!"MWK".equalsIgnoreCase(verification.currency())
                || verification.amount().compareTo(orderTotal) < 0) {
            throw new IllegalStateException("Payment verification did not match this order.");
        }

        payment.setStatus(PaymentStatus.PAID);
        paymentRepository.save(payment);
        orderService.markOrderPaid(payment.getOrderId(), "PAYCHANGU:" + payment.getTxRef());
        return "success";
    }

    @Transactional
    public PaidOrderResult markDemoOrderPaid(Long orderId) {
        OrderResponse order = orderService.getOrderById(orderId);
        if (!"PENDING".equals(order.getStatus())) {
            if ("PAID".equals(order.getStatus())) {
                return paidOrder(order, "PAID");
            }
            throw new IllegalStateException("Only PENDING orders can be marked PAID.");
        }
        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setAmount(order.getTotalAmount().doubleValue());
        payment.setTxRef("DEMO-" + UUID.randomUUID());
        payment.setProvider(PaymentProvider.DEMO);
        payment.setStatus(PaymentStatus.PAID);
        payment.setCreatedAt(LocalDateTime.now());
        paymentRepository.save(payment);
        orderService.markOrderPaid(orderId, "DEMO:" + payment.getTxRef());
        return paidOrder(orderService.getOrderById(orderId), "PAID");
    }

    @Transactional(readOnly = true)
    public DemoCheckout getDemoCheckout(String txRef) {
        if (!isDemoMode()) {
            throw new NotFoundException("Demo payment not found.");
        }
        Payment payment = paymentRepository.findByTxRef(txRef)
                .filter(value -> value.getProvider() == PaymentProvider.DEMO)
                .orElseThrow(() -> new NotFoundException("Demo payment not found."));
        if (payment.getStatus() != PaymentStatus.PENDING
                && payment.getStatus() != PaymentStatus.PAID) {
            throw new IllegalStateException("This demo payment is no longer pending.");
        }
        return new DemoCheckout(payment.getTxRef(), BigDecimal.valueOf(payment.getAmount()),
                payment.getStatus().name());
    }

    @Transactional
    public void completeDemoPayment(String txRef) {
        if (!isDemoMode()) {
            throw new NotFoundException("Demo payment not found.");
        }
        Payment payment = paymentRepository.findByTxRefForUpdate(txRef)
                .filter(value -> value.getProvider() == PaymentProvider.DEMO)
                .orElseThrow(() -> new NotFoundException("Demo payment not found."));
        if (payment.getStatus() == PaymentStatus.PAID) {
            return;
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new IllegalStateException("This demo payment is no longer pending.");
        }
        payment.setStatus(PaymentStatus.PAID);
        paymentRepository.save(payment);
        orderService.markOrderPaid(payment.getOrderId(), "DEMO:" + payment.getTxRef());
    }

    private boolean isDemoMode() {
        return demoPaymentEnabled && environment.acceptsProfiles(Profiles.of("dev"));
    }

    private PaidOrderResult paidOrder(OrderResponse order, String paymentStatus) {
        Long ownerId = userRepository.findByEmail(
                        SecurityContextHolder.getContext().getAuthentication().getName())
                .orElseThrow(() -> new NotFoundException("User not found."))
                .getId();
        List<TicketResponse> tickets = ticketQueryService.getOrderTickets(order.getId(), ownerId);
        return new PaidOrderResult(order, tickets, paymentStatus);
    }

    private String[] splitName(String fullName) {
        String[] parts = fullName.trim().split("\\s+", 2);
        return new String[] {parts[0], parts.length > 1 ? parts[1] : ""};
    }

    public record CheckoutResult(BigDecimal amount, String txRef, String checkoutUrl) {
    }

    public record PaidOrderResult(OrderResponse order, List<TicketResponse> tickets, String paymentStatus) {
    }

    public record DemoCheckout(String txRef, BigDecimal amount, String status) {
    }
}
