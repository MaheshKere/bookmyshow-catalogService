package com.bookmyshow.payment.payment;

import com.bookmyshow.payment.messaging.PaymentEvent;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 36) private String paymentReference;
    @Column(nullable = false, unique = true, length = 36) private String bookingReference;
    @Column(nullable = false, length = 100) private String subject;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private PaymentStatus status;
    @Column(nullable = false) private Instant expiresAt;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    @Version private long version;

    public Payment(PaymentEvent event) {
        paymentReference = UUID.randomUUID().toString();
        bookingReference = event.bookingReference();
        subject = event.subject();
        amount = event.amount();
        currency = event.currency();
        expiresAt = event.expiresAt();
        status = PaymentStatus.PENDING;
    }
    public void complete(PaymentStatus result) {
        if (result == null || result == PaymentStatus.PENDING) throw new IllegalArgumentException("A terminal result is required");
        if (status == result) return;
        if (status != PaymentStatus.PENDING) throw new IllegalStateException("Payment is already terminal");
        status = result;
    }
    @PrePersist void created() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void updated() { updatedAt = Instant.now(); }
}