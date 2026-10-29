package com.sam.apnasamaan.driver.dto;

import com.sam.apnasamaan.auth.dto.AuthResponse;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Returned after multipart driver registration: JWT tokens + initial PENDING driver profile")
public record DriverRegistrationResponse(
        AuthResponse auth,
        DriverProfileResponse driverProfile
) {
}
