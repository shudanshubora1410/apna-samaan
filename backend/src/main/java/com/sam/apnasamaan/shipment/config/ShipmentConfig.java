package com.sam.apnasamaan.shipment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        PricingProperties.class,
        MapsProperties.class,
        GroqProperties.class
})
public class ShipmentConfig {
}
