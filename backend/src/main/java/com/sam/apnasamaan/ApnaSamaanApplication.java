package com.sam.apnasamaan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class ApnaSamaanApplication {

    public static void main(String[] args) {
        // Store all timestamps in UTC so server location never skews shipment/payment times
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(ApnaSamaanApplication.class, args);
    }
}
