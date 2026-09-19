package com.bookmyshow.booking.reservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "booking.expiration.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpirationScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReservationExpirationScheduler.class);
    private final ReservationService service;

    public ReservationExpirationScheduler(ReservationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${booking.expiration.delay-ms:30000}")
    public void expireReservations() {
        for (Long id : service.findExpirationCandidates()) {
            try {
                // External proxy invocation: each reservation expires in its own transaction.
                service.expire(id);
            } catch (RuntimeException exception) {
                log.warn("Could not expire reservation {}; next sweep will retry", id, exception);
            }
        }
    }
}
