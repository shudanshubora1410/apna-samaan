package com.sam.apnasamaan.shipment.service;

import com.sam.apnasamaan.common.exception.BadRequestException;
import com.sam.apnasamaan.shipment.config.PricingProperties;
import com.sam.apnasamaan.shipment.dto.VehicleOptionQuoteResponse;
import com.sam.apnasamaan.shipment.entity.VehicleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingServiceTest {

    private PricingService pricingService;
    private VehicleType bike;
    private VehicleType cargoAuto;
    private VehicleType miniTruck;

    @BeforeEach
    void setUp() {
        pricingService = new PricingService(new PricingProperties(new BigDecimal("18.00")));

        bike = VehicleType.builder()
                .id(1L).code("BIKE").name("Bike")
                .maxWeightKg(new BigDecimal("20.00"))
                .maxLengthCm(40).maxWidthCm(40).maxHeightCm(40)
                .baseFare(new BigDecimal("40.00"))
                .perKmRate(new BigDecimal("8.50"))
                .build();

        cargoAuto = VehicleType.builder()
                .id(2L).code("CARGO_AUTO").name("Cargo Auto")
                .maxWeightKg(new BigDecimal("500.00"))
                .maxLengthCm(170).maxWidthCm(120).maxHeightCm(120)
                .baseFare(new BigDecimal("180.00"))
                .perKmRate(new BigDecimal("18.00"))
                .build();

        miniTruck = VehicleType.builder()
                .id(3L).code("MINI_TRUCK").name("Mini Truck")
                .maxWeightKg(new BigDecimal("1000.00"))
                .maxLengthCm(220).maxWidthCm(150).maxHeightCm(150)
                .baseFare(new BigDecimal("350.00"))
                .perKmRate(new BigDecimal("26.00"))
                .build();
    }

    @Test
    void calculateFare_computesDeterministicInrBreakdownWithAndWithoutGst() {
        // Cargo Auto: base 180.00 + (15.25 km * 18.00 = 274.50) = 454.50 subtotal
        PricingService.FareBreakdown withoutGst = pricingService.calculateFare(
                cargoAuto, new BigDecimal("15.25"), false
        );
        assertThat(withoutGst.baseFare()).isEqualByComparingTo("180.00");
        assertThat(withoutGst.distanceFare()).isEqualByComparingTo("274.50");
        assertThat(withoutGst.subtotalFare()).isEqualByComparingTo("454.50");
        assertThat(withoutGst.gstAmount()).isEqualByComparingTo("0.00");
        assertThat(withoutGst.totalFare()).isEqualByComparingTo("454.50");

        // With 18% GST: 454.50 * 0.18 = 81.81 -> total 536.31
        PricingService.FareBreakdown withGst = pricingService.calculateFare(
                cargoAuto, new BigDecimal("15.25"), true
        );
        assertThat(withGst.gstRatePercent()).isEqualByComparingTo("18.00");
        assertThat(withGst.gstAmount()).isEqualByComparingTo("81.81");
        assertThat(withGst.totalFare()).isEqualByComparingTo("536.31");
    }

    @Test
    void selectAndValidateVehicle_enforcesCapacityFloorAndRejectsInvalidCodes() {
        List<VehicleType> all = List.of(bike, cargoAuto, miniTruck);
        List<VehicleType> eligible = List.of(cargoAuto, miniTruck);

        // Blank code defaults to recommended minimum
        assertThat(pricingService.selectAndValidateVehicle(null, all, eligible, cargoAuto))
                .isEqualTo(cargoAuto);

        // Customer may choose a larger eligible tier
        assertThat(pricingService.selectAndValidateVehicle("mini_truck", all, eligible, cargoAuto))
                .isEqualTo(miniTruck);

        // Customer cannot force an undersized Bike for a Cargo Auto load
        assertThatThrownBy(() -> pricingService.selectAndValidateVehicle("BIKE", all, eligible, cargoAuto))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Minimum required vehicle is 'CARGO_AUTO'");

        // Unknown vehicle code fails cleanly
        assertThatThrownBy(() -> pricingService.selectAndValidateVehicle("DRONE", all, eligible, cargoAuto))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Allowed codes");
    }

    @Test
    void buildOptionQuotes_marksRecommendedVehicleOption() {
        List<VehicleOptionQuoteResponse> quotes = pricingService.buildOptionQuotes(
                List.of(cargoAuto, miniTruck),
                new BigDecimal("10.00"),
                false,
                "CARGO_AUTO"
        );

        assertThat(quotes).hasSize(2);
        assertThat(quotes.get(0).vehicleTypeCode()).isEqualTo("CARGO_AUTO");
        assertThat(quotes.get(0).recommended()).isTrue();
        assertThat(quotes.get(0).totalFare()).isEqualByComparingTo("360.00");
        assertThat(quotes.get(1).vehicleTypeCode()).isEqualTo("MINI_TRUCK");
        assertThat(quotes.get(1).recommended()).isFalse();
    }
}
