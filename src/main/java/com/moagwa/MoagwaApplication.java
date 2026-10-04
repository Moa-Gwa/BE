package com.moagwa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MoagwaApplication {
    public static void main(String[] args) {
        SpringApplication.run(MoagwaApplication.class, args);
    }
}
