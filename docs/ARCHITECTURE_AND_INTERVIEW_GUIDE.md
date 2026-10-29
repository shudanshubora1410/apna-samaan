# Apna Samaan — Architecture & Technical Interview Guide

This document summarizes the key engineering decisions, concurrency safeguards, security patterns, and interview talking points behind **Apna Samaan** so every subsystem can be explained clearly from first principles.

---

## 1. Architectural Principles

1. **Strict Layered Package-by-Feature Design (`com.sam.apnasamaan`)**
   - Feature modules: `auth`, `customer`, `driver`, `shipment`, `payment`, `admin`, and `common`.
   - Controllers are thin HTTP adapters (`@Valid` request DTO -> Service -> Response DTO). JPA entities are **never** serialized directly to clients, and `spring.jpa.open-in-view=false` prevents accidental lazy-loading SQL inside controllers.
2. **Schema Ownership via Flyway (`ddl-auto=validate`)**
   - Every table, index, and foreign key is versioned in `V1`–`V6` SQL migrations. Hibernate only validates at startup that entity mappings match MySQL 8 — preventing silent schema drift in production.
3. **Exact Monetary Arithmetic (`BigDecimal`)**
   - All fares, rates, and GST figures use `BigDecimal` rounded to 2 decimal places (`RoundingMode.HALF_UP`) in INR (`DECIMAL(10,2)` in MySQL) and convert to exact integer paise (`multiply(100).longValueExact()`) for Razorpay APIs — eliminating binary floating-point rounding bugs (`0.1 + 0.2 != 0.3`).
4. **External I/O Isolation & Graceful Fallbacks**
   - External HTTP calls (OSRM, Nominatim, Groq, Google OAuth2, Gmail SMTP) use strict connect/read timeouts (`3s–6s`) and run **outside** long-lived DB transactions wherever possible:
     - **OSRM down?** Falls back to `Haversine distance * 1.30` road factor.
     - **Groq down or unconfigured?** Falls back to deterministic 3D bounding-box + weight rule.
     - **Gmail SMTP slow or down?** Runs asynchronously *after* DB commit (`TransactionSynchronization.afterCommit`) and logs failures without rolling back user transactions.

---

## 2. Deep-Dive Subsystems

### A. Dual-Token Stateless Authentication + Reuse Detection
- **Access Token (15 minutes):** Stateless HS256 JWT (`sub=userId`, `email`, `role`). Secret must be `>= 32` bytes or startup fails fast.
- **Refresh Token (7 days):** Cryptographically random 256-bit token; **only its SHA-256 hex digest** is persisted in `refresh_tokens`.
- **Rotation & Theft Detection:** Calling `/api/v1/auth/refresh` marks the old token `revoked=true`, records `replaced_by_hash`, and issues a fresh pair. If an attacker (or victim) replays an already-rotated refresh token, `AuthService` immediately revokes **all** active sessions for that user (`noRollbackFor = UnauthorizedException.class`).

### B. Customer Google OAuth2 Without Server Sessions
- **Login CSRF Defense:** `/api/v1/auth/google/authorize` generates a random nonce, sets it in a short-lived `HttpOnly; SameSite=Lax` cookie (`AS_OAUTH_STATE`), and signs a 10-minute `oauth_state` JWT with a domain-separated HMAC key (`jwtSecret + ":google-oauth-state"`).
- **One-Time 60s Handoff Ticket:** Instead of redirecting to `customer.html?accessToken=...` (which leaks tokens into browser history, proxy logs, and `Referer` headers), `/callback` redirects to `login.html?oauth_ticket=...` and the frontend exchanges the single-use hashed ticket via `POST /api/v1/auth/google/exchange`.
- **Strict Role Boundary:** Only `CUSTOMER` accounts can be created or linked via Google (`email_verified=true` required). `DRIVER` (requires `DL`+`RC` KYC) and `ADMIN` accounts are strictly rejected.

### C. Private KYC Document Storage
- Multipart uploads (`DL`, `RC`) enforce a `5 MB` business cap and inspect **magic bytes** (`FF D8 FF` JPEG, `89 50 4E 47` PNG, `%PDF-` PDF) rather than trusting client `Content-Type` headers or file extensions.
- Files are written under `FILE_STORAGE_DIR` (outside static web roots) using random `UUID` filenames (`e.g., 8f14e45f-...pdf`) to prevent path traversal and filename collisions. Downloads stream through `GET /api/v1/driver/documents/{id}` only after verifying owner-driver or `ADMIN` authorization.

### D. Two-Layer Driver Matching & Concurrency Control
- Eligible pool: `verification_status = APPROVED`, `online = true`, `available = true`, matching `city`, matching `vehicle_type_id`, and no prior offer for the same shipment — sorted by **Haversine distance** to pickup.
- Offers are dispatched **sequentially** (`60s` expiry each). `DriverMatchingScheduler` sweeps expired offers every `5s`, advances to the next nearest driver, and marks shipments `FAILED` (triggering an automatic full Razorpay refund) if `180s` elapses without assignment.
- **Preventing Double-Assignment / Double-Booking:**
  1. **Layer 1 (Database Atomic Claim):** `UPDATE driver_profiles SET available = false WHERE id = :id AND verification_status = 'APPROVED' AND online = true AND available = true`. If affected rows == `0`, a concurrent request already claimed the driver.
  2. **Layer 2 (JPA Optimistic Locking):** `@Version` on both `Shipment` and `DriverJobOffer` guarantees at most one transaction can transition a shipment from `SEARCHING_DRIVER` to `ASSIGNED`.

### E. Razorpay Payment Integrity & Idempotent Webhooks
- Order amount is **always** read from server-calculated `shipment.totalFare` in MySQL — never from client request bodies.
- Checkout verification computes `HMAC-SHA256(razorpayOrderId + "|" + razorpayPaymentId, keySecret)` using constant-time comparison (`MessageDigest.isEqual`).
- Webhook endpoint (`POST /api/v1/payments/webhook`) reads the **raw request bytes** (`byte[]`) to verify `X-Razorpay-Signature` with `webhookSecret` and deduplicates retries via `last_webhook_event_key` so duplicate `payment.captured` deliveries are harmless no-ops.

---

## 3. Top 20 Technical Interview Q&A

1. **Why use both a 15-minute JWT access token and a 7-day DB-backed refresh token?**
   Stateless JWTs avoid a DB hit on every API call, while short 15-minute expiry bounds the window of a leaked access token. Storing SHA-256 hashes of 7-day refresh tokens gives us instant server-side logout, rotation, and reuse/theft detection.
2. **Why hash refresh tokens and password-reset tokens with SHA-256 instead of BCrypt?**
   High-entropy random tokens (`256 bits` from `SecureRandom`) cannot be dictionary-attacked like human passwords, so fast deterministic SHA-256 allows indexed `O(1)` lookup by `token_hash` while still protecting raw tokens if the database is read-leaked.
3. **How do you prevent user enumeration in Login and Forgot Password?**
   `login` returns the identical `401 Invalid email or password` message (and runs a dummy BCrypt hash when an email is missing or Google-only to equalize response latency). `forgot-password` always returns `200 OK` with the same generic confirmation message.
4. **Why store `BigDecimal` instead of `double` for INR fares?**
   IEEE-754 `double` cannot represent base-10 fractions like `0.10` exactly, causing cumulative paise drift when multiplying distances and `18%` GST. `BigDecimal` with `DECIMAL(10,2)` guarantees exact rupee-and-paise math.
5. **Why is Groq AI restricted from setting or modifying prices?**
   LLMs are probabilistic and vulnerable to prompt injection inside package descriptions. Server-side rules compute exact 3D bounding-box eligibility and deterministic fares first; Groq can only confirm the minimum fitting vehicle or recommend a *larger* eligible vehicle, never a smaller one or a custom fare.
6. **How does your 3D package-dimension check handle rotated boxes?**
   Both `[length, width, height]` of the package and `[maxLength, maxWidth, maxHeight]` of each vehicle are sorted ascending before element-wise comparison, naturally modeling 90-degree cargo rotation during loading.
7. **How do you validate KYC uploads beyond checking `.pdf` or `.jpg` extensions?**
   `DocumentStorageService` inspects the leading magic bytes of the uploaded stream (`FF D8 FF`, `89 50 4E 47`, or `%PDF-`), enforces size `<= 5 MB`, strips original filenames, and writes outside the static web root using a `UUID` filename.
8. **Why didn't you serve KYC scans directly from `/assets/uploads/...`?**
   Driving Licences and Vehicle RCs are sensitive PII. Serving them through an authenticated controller (`GET /api/v1/driver/documents/{id}`) enforces that only the owning driver or an `ADMIN` can view them.
9. **What happens if two requests try to accept an offer at the exact same millisecond?**
   The conditional `UPDATE driver_profiles SET available = false WHERE ... AND available = true` atomically serializes driver claiming in MySQL, and `@Version` optimistic locking on `Shipment` and `DriverJobOffer` aborts any losing concurrent transaction with `409 Conflict`.
10. **Why verify Razorpay webhooks against the raw `byte[]` payload instead of re-serializing a DTO?**
    HMAC-SHA256 signatures depend on every byte, whitespace character, and key order sent by Razorpay. Parsing into a Java object and re-serializing with Jackson alters formatting and invalidates valid signatures.
11. **What makes `/api/v1/payments/webhook` idempotent if Razorpay delivers `payment.captured` three times?**
    Payment gateways use at-least-once delivery. We compute a deterministic event key (`X-Razorpay-Event-Id` or event+paymentId) and skip processing when `payment.lastWebhookEventKey` already matches or `payment.status == CAPTURED`.
12. **When are automatic refunds triggered without Admin intervention?**
    (1) When a customer cancels a paid shipment *before* `PICKED_UP` (`SEARCHING_DRIVER` or `ASSIGNED`), and (2) when `DriverMatchingScheduler` transitions a shipment to `FAILED` after the search timeout expires with no driver accepting.
13. **Why mask driver GPS coordinates once a shipment reaches `DELIVERED`?**
    Once delivery completes, the driver is off that customer's job. Returning `null` for `driverLat`/`driverLng` after `DELIVERED` protects the driver's personal post-trip location privacy while preserving route pickup/drop pins.
14. **Why send transactional emails inside `TransactionSynchronization.afterCommit()` on an async executor?**
    Sending SMTP synchronously inside `@Transactional` holds open a MySQL `HikariPool` connection for 2–3 seconds during TLS handshakes and could email a customer even if the DB commit later fails. `afterCommit` + async dispatch guarantees zero false emails and `<50ms` API latency.
15. **Why restrict Google OAuth2 sign-in strictly to `CUSTOMER` accounts?**
    Drivers require upfront vehicle + `DL`/`RC` KYC uploads and Admin approval (`PENDING -> APPROVED`), while Admins hold refund and KYC privileges. Scoping consumer OAuth to Customers gives shippers 1-click onboarding without weakening Driver KYC or Admin security.
16. **Why use a 60-second single-use `oauth_ticket` instead of putting JWTs in the callback redirect URL?**
    URL query strings persist in browser history, server logs, and `Referer` headers. Exchanging a short-lived hashed one-time ticket via `POST /api/v1/auth/google/exchange` keeps real JWT and refresh tokens strictly inside JSON bodies.
17. **Why disable `spring.jpa.open-in-view` (`OSIV`)?**
    With OSIV enabled, Hibernate keeps a DB connection open through view/JSON rendering, hiding `N+1` queries and exhausting connection pools under load. Disabling OSIV forces explicit `JOIN FETCH` queries inside the service transaction boundary.
18. **How does the frontend prevent Cross-Site Scripting (XSS) without a heavy framework?**
    All dynamic API values (addresses, names, notes, filenames) are rendered via `element.textContent` and a tiny `el()` DOM builder in `js/ui.js` rather than `innerHTML` string concatenation, and server emails escape untrusted strings via `HtmlUtils.htmlEscape`.
19. **Why use a multi-stage `Dockerfile` running as a non-root user?**
    Stage 1 (`maven:3.9.9-eclipse-temurin-17`) compiles the JAR with cached Maven layers; Stage 2 (`eclipse-temurin:17-jre-jammy`) ships only the JRE + artifact (smaller attack surface and image size) and runs as UID `10001` (`apnasamaan`) so container processes never run as `root`.
20. **What would you change first to scale Apna Samaan horizontally behind a load balancer?**
    The stateless JWT filter and MySQL optimistic/conditional locks already work across multiple instances! The three single-node components to externalize next would be: (1) `AuthRateLimiter` and OAuth one-time tickets -> **Redis** (`INCR`/`EXPIRE` and `GETDEL`), (2) `DriverMatchingScheduler` -> **ShedLock** (so only one replica sweeps timeouts at a time), and (3) `DocumentStorageService` -> **S3-compatible object storage** with pre-signed short-lived streams.
