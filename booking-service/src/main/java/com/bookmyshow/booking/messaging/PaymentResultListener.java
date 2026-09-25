package com.bookmyshow.booking.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentResultListener {
    private final EventCodec codec;
    private final PaymentResultHandler handler;
    public PaymentResultListener(EventCodec codec, PaymentResultHandler handler) { this.codec = codec; this.handler = handler; }
    @KafkaListener(topics = PaymentEvent.RESULTS, groupId = "booking-service-v1")
    public void receive(ConsumerRecord<String, String> record) { handler.accept(codec.read(record.key(), record.value())); }
}