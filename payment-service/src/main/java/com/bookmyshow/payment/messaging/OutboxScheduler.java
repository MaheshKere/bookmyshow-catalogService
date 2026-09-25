package com.bookmyshow.payment.messaging;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "messaging.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {
    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);
    private final OutboxPublisher publisher;
    public OutboxScheduler(OutboxPublisher publisher) { this.publisher = publisher; }
    @Scheduled(fixedDelayString = "${messaging.outbox.delay-ms:1000}")
    public void publish() {
        try {
            for (int i = 0; i < 100 && publisher.publishOne(); i++) { /* Each proxy call commits separately. */ }
        } catch (RuntimeException exception) {
            log.warn("outboxRetry failureType={}", exception.getClass().getSimpleName());
        }
    }
}
