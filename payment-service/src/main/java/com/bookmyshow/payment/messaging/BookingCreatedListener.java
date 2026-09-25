package com.bookmyshow.payment.messaging;

import com.bookmyshow.payment.payment.PaymentService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class BookingCreatedListener {
    private final EventCodec codec;
    private final PaymentService service;
    public BookingCreatedListener(EventCodec codec, PaymentService service) { this.codec = codec; this.service = service; }
    @KafkaListener(topics = PaymentEvent.BOOKINGS, groupId = "payment-service-v1")
    public void receive(ConsumerRecord<String, String> record) {
        service.accept(codec.read(record.key(), record.value()));
    }
}