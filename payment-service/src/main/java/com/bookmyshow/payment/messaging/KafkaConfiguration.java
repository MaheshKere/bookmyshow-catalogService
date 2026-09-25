package com.bookmyshow.payment.messaging;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfiguration {
    @Bean
    KafkaAdmin.NewTopics topics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(PaymentEvent.BOOKINGS).partitions(3).replicas(1).build(),
                TopicBuilder.name(PaymentEvent.RESULTS).partitions(3).replicas(1).build(),
                TopicBuilder.name(PaymentEvent.BOOKINGS + ".DLT").partitions(3).replicas(1).build(),
                TopicBuilder.name(PaymentEvent.RESULTS + ".DLT").partitions(3).replicas(1).build());
    }
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        // Never acknowledge recovery if the DLT broker write failed.
        recoverer.setFailIfSendResultIsError(true);
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
        handler.addNotRetryableExceptions(PermanentEventException.class);
        handler.setResetStateOnExceptionChange(false);
        return handler;
    }
}
