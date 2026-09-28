package com.bookmyshow.notification.messaging;

import com.bookmyshow.notification.notification.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class BookingConfirmedListener {
    private final EventCodec codec;
    private final NotificationService service;
    public BookingConfirmedListener(EventCodec codec, NotificationService service) {
        this.codec = codec; this.service = service;
    }
    @KafkaListener(topics = BookingConfirmedEvent.TOPIC, groupId = "notification-service-v1")
    public void receive(ConsumerRecord<String, String> record) {
        service.accept(codec.read(record.key(), record.value()));
    }
}
