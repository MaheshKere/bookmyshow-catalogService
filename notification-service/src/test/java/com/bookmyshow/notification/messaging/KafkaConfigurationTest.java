package com.bookmyshow.notification.messaging;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KafkaConfigurationTest {
    @Test @SuppressWarnings("unchecked")
    void failedDltPublicationDoesNotRecoverSourceRecord() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("DLT unavailable")));
        var handler = new KafkaConfiguration().kafkaErrorHandler(template);
        Consumer<String, String> consumer = mock(Consumer.class);
        var container = mock(MessageListenerContainer.class);
        var record = new ConsumerRecord<String, String>(BookingConfirmedEvent.TOPIC, 0, 0L, "booking", "{}");
        assertThat(handler.handleOne(new PermanentEventException("invalid"), record, consumer, container)).isFalse();
        verify(template).send(any(ProducerRecord.class));
        verify(consumer, never()).commitSync();
    }
}
