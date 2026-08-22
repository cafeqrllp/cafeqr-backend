package com.restaurant.pos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.retry.annotation.EnableRetry;

@EnableAsync
@EnableRetry(order = 99)
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.restaurant.pos", exclude = {
     org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration.class
})
public class CafeQrBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(CafeQrBackendApplication.class, args);
    }

}

