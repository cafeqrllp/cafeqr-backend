package com.restaurant.pos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.retry.annotation.EnableRetry;

@EnableAsync
@EnableRetry(order = 99)
@SpringBootApplication(scanBasePackages = "com.restaurant.pos")
public class CafeQrBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(CafeQrBackendApplication.class, args);
    }

}
