package com.bookmyshow.payment.payment;

import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final PaymentService service;
    public PaymentController(PaymentService service) { this.service = service; }
    @GetMapping("/booking/{reference}")
    public PaymentService.PaymentView get(@PathVariable @Size(max = 36) String reference, @AuthenticationPrincipal Jwt jwt) {
        return service.get(reference, jwt.getSubject());
    }
}