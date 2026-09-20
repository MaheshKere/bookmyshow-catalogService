package com.bookmyshow.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import java.time.Clock;

@SpringBootApplication
public class IdentityServiceApplication {
    public static void main(String[] args) { SpringApplication.run(IdentityServiceApplication.class, args); }
    @Bean Clock clock() { return Clock.systemUTC(); }
}
