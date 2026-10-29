package com.sam.apnasamaan.common.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DummyErrorController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void validationFailure_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/test-errors/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "",
                                  "email": "not-an-email"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value("/api/v1/test-errors/validate"))
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
    }

    @Test
    void malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/test-errors/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ broken-json "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed JSON request body or invalid field format"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void domainExceptions_mapToExpectedHttpStatuses() throws Exception {
        mockMvc.perform(get("/api/v1/test-errors/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Shipment #42 not found"));

        mockMvc.perform(get("/api/v1/test-errors/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(get("/api/v1/test-errors/forbidden-domain"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(get("/api/v1/test-errors/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(get("/api/v1/test-errors/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(get("/api/v1/test-errors/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(get("/api/v1/test-errors/service-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    void parameterAndMethodErrors_return400And405() throws Exception {
        mockMvc.perform(get("/api/v1/test-errors/type-mismatch/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'id'"));

        mockMvc.perform(get("/api/v1/test-errors/missing-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Required parameter 'city' is missing"));

        mockMvc.perform(delete("/api/v1/test-errors/not-found"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void multipartAndFormBindingErrors_return400And413() throws Exception {
        mockMvc.perform(post("/api/v1/test-errors/form")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("name", "")
                        .param("email", "bad-email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists());

        mockMvc.perform(multipart("/api/v1/test-errors/upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Required file upload 'drivingLicence' is missing"));

        mockMvc.perform(get("/api/v1/test-errors/upload-too-large"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413));

        mockMvc.perform(post("/api/v1/test-errors/multipart-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    void unexpectedException_returns500WithoutLeakingInternalMessage() throws Exception {
        mockMvc.perform(get("/api/v1/test-errors/crash"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("An unexpected server error occurred"))
                .andExpect(jsonPath("$.message", not(containsString("Sensitive internal SQL detail"))));
    }

    record SamplePayload(
            @NotBlank(message = "Name is required") String name,
            @NotBlank @Email(message = "Valid email is required") String email
    ) {
    }

    @RestController
    @RequestMapping("/api/v1/test-errors")
    static class DummyErrorController {

        @PostMapping("/validate")
        String validate(@Valid @RequestBody SamplePayload payload) {
            return payload.name();
        }

        @GetMapping("/not-found")
        String notFound() {
            throw new ResourceNotFoundException("Shipment #42 not found");
        }

        @GetMapping("/conflict")
        String conflict() {
            throw new ConflictException("Email already registered");
        }

        @GetMapping("/forbidden-domain")
        String forbiddenDomain() {
            throw new ForbiddenOperationException("Cannot access another user's shipment");
        }

        @GetMapping("/bad-request")
        String badRequest() {
            throw new BadRequestException("Illegal status transition");
        }

        @GetMapping("/access-denied")
        String accessDenied() {
            throw new AccessDeniedException("Role check failed");
        }

        @GetMapping("/optimistic-lock")
        String optimisticLock() {
            throw new ObjectOptimisticLockingFailureException("Shipment", 42L);
        }

        @GetMapping("/service-unavailable")
        String serviceUnavailable() {
            throw new ServiceUnavailableException("Payment gateway unreachable");
        }

        @GetMapping("/type-mismatch/{id}")
        String typeMismatch(@PathVariable Long id) {
            return String.valueOf(id);
        }

        @GetMapping("/missing-param")
        String missingParam(@RequestParam String city) {
            return city;
        }

        @PostMapping("/form")
        String validateForm(@Valid @ModelAttribute SamplePayload payload) {
            return payload.name();
        }

        @PostMapping("/upload")
        String upload(@RequestPart("drivingLicence") MultipartFile file) {
            return file.getOriginalFilename();
        }

        @GetMapping("/upload-too-large")
        String uploadTooLarge() {
            throw new MaxUploadSizeExceededException(5 * 1024 * 1024L);
        }

        @PostMapping(value = "/multipart-only", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        String multipartOnly() {
            return "ok";
        }

        @GetMapping("/crash")
        String crash() {
            throw new IllegalStateException("Sensitive internal SQL detail");
        }
    }
}
