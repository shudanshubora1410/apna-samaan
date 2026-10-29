# Apna Samaan — Goods Delivery & Logistics Platform

**Apna Samaan** is a full-stack intra-city and inter-city commercial goods-delivery platform built for Indian logistics corridors (`CUSTOMER`, `DRIVER`, and `ADMIN` workspaces). It combines deterministic server-side INR freight pricing, interactive OpenStreetMap/OSRM road routing, rule-clamped Groq AI vehicle fitment confirmation, Razorpay Test Mode checkout & automated refunds, sequential Haversine driver dispatch with 60-second job offers, live GPS tracking, KYC document verification, Customer Google OAuth2 sign-in, and post-commit HTML transactional emails.

---

## Core Capabilities by Role

### 1. Shipper (`CUSTOMER`)
- **Instant Route & Fare Quotes**: Pin pickup and drop-off stops across India on an interactive Leaflet + OpenStreetMap canvas with Nominatim place search, OSRM road-distance calculation (with automatic `1.30x` Haversine road-factor fallback), optional `18%` GST breakdown, and Groq AI vehicle fitment confirmation (`Bike`, `Cargo Auto`, `Mini Truck`, `Truck`).
- **Google OAuth2 & Email Onboarding**: Sign in with one click via Google OAuth2 (stateless CSRF-protected redirect + single-use handoff ticket) or standard email + BCrypt password.
- **Secure Freight Checkout & Refunds**: Pay via Razorpay Test Mode (HMAC-SHA256 signature verification + idempotent webhook capture, plus optional local simulation mode for offline dev). Pre-pickup cancellations and matching timeouts trigger automatic full refunds.
- **Live GPS Tracking & Tax Invoices**: Poll live driver coordinates every 5 seconds during active transit (with automatic post-delivery coordinate masking for driver privacy), inspect chronological status history, manage saved pickup hubs & B2B GSTIN details, and download itemized freight receipts.

### 2. Driver Partner (`DRIVER`)
- **KYC Onboarding & Verification**: Upload Driving Licence (`DL`) and Vehicle Registration Certificate (`RC`) scans (`JPEG`/`PNG`/`PDF`, `<= 5 MB`, magic-byte MIME verified, stored with UUID filenames outside the static web root).
- **Sequential 60-Second Dispatch Radar**: Approved drivers toggle `ONLINE` in their operating city, broadcast GPS coordinates, and receive Haversine-nearest 60-second job offers protected by a 2-layer concurrency guard (atomic SQL claim + JPA `@Version` optimistic locking).
- **Step-by-Step Trip Execution**: Advance assigned shipments strictly along `ASSIGNED -> PICKED_UP -> IN_TRANSIT -> DELIVERED`, triggering instant post-commit HTML email notifications to the shipper on acceptance and delivery.

### 3. Operations Admin (`ADMIN`)
- **First-Start Bootstrap**: Automatically seeded on initial startup from `ADMIN_EMAIL` and `ADMIN_PASSWORD`.
- **KYC Queue & Fleet Governance**: Inspect private `DL`/`RC` documents via authenticated streaming and approve, reject (allowing driver re-upload), or deactivate driver accounts.
- **Platform Ledger & Refund Controls**: Filter all platform waybills and payment records, inspect status timelines, and issue audited Razorpay refunds when needed.

---

## Tech Stack

| Layer | Technology |
| :--- | :--- |
| **Language & Runtime** | Java 17 LTS |
| **Backend Framework** | Spring Boot 3.4.x (`Spring Web`, `Spring Data JPA`, `Spring Security`, `Bean Validation`, `Spring Mail`) |
| **Database & Migrations** | MySQL 8.0 (`utf8mb4_unicode_ci`), Flyway (`V1`–`V6`), Hibernate `ddl-auto=validate` |
| **Authentication** | Stateless JWT (`JJWT 0.12.x`, 15-min HS256 access token) + 7-day SHA-256 hashed rotating refresh tokens + Customer Google OAuth2 |
| **Payments** | Official `razorpay-java` SDK (Orders, HMAC-SHA256 checkout & raw-body idempotent webhook verification, full refunds) |
| **Maps & Routing** | Leaflet 1.9 + OpenStreetMap tiles + Nominatim geocoding + OSRM driving routes (wrapped behind `RoutingAndGeocodingService`) |
| **AI Vehicle Advisor** | Groq OpenAI-compatible Chat Completions API (`llama-3.3-70b-versatile`) with strict capacity clamping & deterministic fallback |
| **Frontend** | Plain HTML5, CSS3, Vanilla ES Modules (`js/api.js` single client), Bootstrap 5.3 (responsive from `360px`, XSS-safe DOM rendering) |
| **API Documentation** | `springdoc-openapi` (`Swagger UI` at `/swagger-ui.html`, OpenAPI 3 JSON at `/v3/api-docs`) |
| **Testing & Quality** | JUnit 5, Mockito, Spring Boot Test, Testcontainers (MySQL), JaCoCo (`~91%` service line coverage) |
| **DevOps & Packaging** | Multi-stage non-root `Dockerfile`, `docker-compose.yml` (`mysql` + `app`), GitHub Actions CI |

---

## System Architecture

```text
+-----------------------------------------------------------------------------------+
|                     Browser (HTML5 / CSS3 / ES Modules / Leaflet)                 |
|    index.html  |  customer.html  |  driver.html  |  admin.html  |  auth pages     |
+-----------------------------------------+-----------------------------------------+
                                          |  HTTPS / JSON  (centralized js/api.js)
                                          v
+-----------------------------------------------------------------------------------+
|                        Spring Boot 3 Backend (:8080, /api/v1)                     |
|  SecurityFilterChain (Stateless JWT + CORS + RateLimiter + Method @PreAuthorize)  |
|                                                                                   |
|   auth        customer       driver        shipment       payment        admin    |
|  Controllers  ---------->   Services (RBAC, Ownership, State Machine, Pricing)    |
|                                         |                                         |
|                             Spring Data JPA Repositories                          |
+-------------------+---------------------+---------------------+-------------------+
                    |                     |                     |
                    v                     v                     v
          +------------------+  +-------------------+  +----------------------------+
          |    MySQL 8.0     |  | Private Disk Dir  |  | External Integrations      |
          |  Flyway V1 - V6  |  | (UUID DL/RC KYC)  |  | - OSRM & Nominatim         |
          +------------------+  +-------------------+  | - Razorpay Orders/Webhooks |
                                                       | - Groq AI Vehicle Advisor  |
                                                       | - Gmail SMTP (afterCommit) |
                                                       | - Google OAuth2 (Customer) |
                                                       +----------------------------+
```

---

## Shipment Lifecycle State Machine

All status mutations pass through `ShipmentStateMachine` and `ShipmentTransitionService`, recording an immutable audit row in `shipment_status_history` on every transition:

```text
                     Payment Verified / Captured
  [PENDING_PAYMENT] -----------------------------> [SEARCHING_DRIVER]
         |                                                |   |
         | Customer cancels                               |   | No driver accepts
         | before pickup                                  |   | within search timeout
         v                                                |   v
    [CANCELLED] <---- Customer cancels before pickup -----+  [FAILED] (Auto Full Refund)
    (Auto Refund                                          |
     if Captured)                                         | Nearest ONLINE + AVAILABLE
                                                          | driver accepts 60s offer
                                                          v
                                                     [ASSIGNED]  ---> Emails Shipper
                                                          |
                                                          | Driver confirms cargo loaded
                                                          v
                                                    [PICKED_UP]
                                                          |
                                                          | Driver begins route transit
                                                          v
                                                    [IN_TRANSIT]
                                                          |
                                                          | Driver completes handoff
                                                          v
                                                    [DELIVERED]  ---> Emails Shipper
```

---

## Database Schema (Flyway `V1`–`V6`)

| Migration | Tables / Changes | Purpose |
| :--- | :--- | :--- |
| **`V1`** | `users`, `refresh_tokens`, `password_reset_tokens` | Unified accounts (`CUSTOMER`, `DRIVER`, `ADMIN`), SHA-256 hashed rotating 7-day refresh tokens, and 15-min single-use reset tokens |
| **`V2`** | `vehicle_types`, `driver_profiles`, `driver_documents` | Seeded 4-tier commercial fleet (`BIKE`, `CARGO_AUTO`, `MINI_TRUCK`, `TRUCK`), driver KYC/GPS/availability state, and private document metadata |
| **`V3`** | `shipments`, `shipment_status_history` | Bookings, snapshots of server-calculated fares (`BigDecimal`, 2 decimals), optimistic lock `@Version`, and chronological audit trail |
| **`V4`** | `payments` | Razorpay order/payment/refund tracking, paise/INR amounts, and idempotent `last_webhook_event_key` |
| **`V5`** | `driver_job_offers` | Sequential 60-second Haversine-ranked driver offers (`PENDING`, `ACCEPTED`, `DECLINED`, `EXPIRED`, `SUPERSEDED`) |
| **`V6`** | `users` (`auth_provider`, `google_sub`, nullable `password_hash`) | Customer Google OAuth2 identity linking without dummy passwords |

---

## Quickstart Guide

### Prerequisites
- **Option A (Docker Compose — Recommended Quickest Start):** Docker Desktop (or Docker Engine + Compose v2).
- **Option B (Local Development):** JDK **17+** (`17` or `21`), Maven **3.9+**, and MySQL **8.0** (local install or `docker compose up -d mysql`).

### Step 1: Configure `.env`
Copy `.env.example` to `.env` in the repository root and replace the `CHANGE_ME_*` placeholders with your local values (never commit `.env`):

```bash
cp .env.example .env
```

*(On Windows PowerShell: `Copy-Item .env.example .env`)*

Minimum required values in `.env` to boot immediately:
- `MYSQL_ROOT_PASSWORD`, `MYSQL_PASSWORD`, `DB_PASSWORD` — strong local database passwords
- `JWT_SECRET` — any random secret string of **at least 32 characters** (startup fails fast if shorter)
- `ADMIN_PASSWORD` — initial password for the bootstrapped `ADMIN_EMAIL` account
- *(Tip: Set `RAZORPAY_SIMULATION_ENABLED=true` in `.env` during local testing if you want 1-click simulated Razorpay checkout before adding real Razorpay Test keys.)*

### Step 2A: Run Full Stack with Docker Compose (1 Command)

```bash
docker compose up --build -d
```

- Application UI: **`http://localhost:8080`**
- Health Check: **`http://localhost:8080/api/v1/health`**
- Swagger UI: **`http://localhost:8080/swagger-ui.html`**
- Stop stack: `docker compose down` (add `-v` only if you want to wipe MySQL & uploaded KYC volumes)

### Step 2B: Run Locally with Maven + MySQL

1. Start MySQL 8 (either your local Windows/macOS/Linux MySQL 8 service with database `apna_samaan`, or via Docker):
   ```bash
   docker compose up -d mysql
   ```
2. Start the Spring Boot backend from `backend/` (serves `frontend/` automatically on port `8080`):
   ```bash
   cd backend
   mvn spring-boot:run
   ```
3. Open **`http://localhost:8080`** in your browser!

---

## Environment Variables Reference

All configuration lives in `.env` (loaded automatically by Spring Boot and `docker-compose.yml`). Placeholder template in `.env.example`:

| Variable | Required | Default | Description |
| :--- | :---: | :--- | :--- |
| `SERVER_PORT` | No | `8080` | HTTP port for backend API and static frontend |
| `CORS_ALLOWED_ORIGINS` | No | `http://localhost:8080,...` | Comma-separated allowed browser origins (never `*`) |
| `FRONTEND_BASE_URL` | No | `http://localhost:8080` | Base URL used in password-reset & notification email links |
| `DB_URL` | Yes | `jdbc:mysql://localhost:3306/apna_samaan...` | JDBC connection URL for MySQL 8 |
| `DB_USERNAME` / `DB_PASSWORD` | Yes | — | MySQL application credentials |
| `JWT_SECRET` | **Yes** | — | HS256 signing secret (**minimum 32 characters**; startup fails otherwise) |
| `JWT_ACCESS_EXPIRATION_MINUTES` | No | `15` | Short-lived JWT access token lifetime |
| `JWT_REFRESH_EXPIRATION_DAYS` | No | `7` | Rotating SHA-256 hashed refresh token lifetime |
| `ADMIN_EMAIL` | No | `shudanshubora1410@gmail.com` | Email for first-boot Admin account seeding |
| `ADMIN_PASSWORD` | **Yes** | — | Password used if `ADMIN_EMAIL` does not exist yet |
| `FILE_STORAGE_DIR` | No | `./uploads/driver-documents` | Private filesystem directory outside static web root for Driver KYC files |
| `MAIL_HOST` / `MAIL_PORT` | No | `smtp.gmail.com` / `587` | SMTP server for password reset & shipment lifecycle emails |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | Optional | `teamapnasamaan@gmail.com` / blank | Gmail address & 16-char Google App Password (skips SMTP cleanly if blank) |
| `PRICING_GST_PERCENT` | No | `18.00` | Optional GST percentage applied when customer opts in |
| `NOMINATIM_BASE_URL` / `OSRM_BASE_URL` | No | Public OSM endpoints | Geocoding & road-routing endpoints (falls back to Haversine `1.30x` on timeout) |
| `GROQ_API_KEY` | Optional | blank | Groq API key (`gsk_...`) for AI vehicle fitment confirmation (falls back to rule-based) |
| `GROQ_MODEL` | No | `llama-3.3-70b-versatile` | OpenAI-compatible Groq model identifier |
| `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET` | Optional | blank | Razorpay Test Mode credentials (`rzp_test_...`) |
| `RAZORPAY_WEBHOOK_SECRET` | Optional | blank | Secret for verifying raw `X-Razorpay-Signature` webhooks |
| `RAZORPAY_SIMULATION_ENABLED` | No | `false` | Enables `/api/v1/payments/simulate-checkout` for local dev without Razorpay keys |
| `MATCHING_OFFER_TIMEOUT_SECONDS` | No | `60` | Per-driver job offer countdown window |
| `MATCHING_SEARCH_TIMEOUT_SECONDS` | No | `180` | Max search window before transitioning to `FAILED` + auto-refund |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Optional | blank | Google Cloud OAuth 2.0 Web Client credentials for Customer sign-in |
| `GOOGLE_REDIRECT_URI` | No | `http://localhost:8080/api/v1/auth/google/callback` | Authorized Google OAuth2 callback URL |

---

## Optional External Integration Setup

1. **Gmail SMTP (Forgot Password + Driver Accepted + Order Delivered Emails):**
   Enable 2-Step Verification on your Google account, generate a 16-character **App Password** at `myaccount.google.com/apppasswords`, and set `MAIL_USERNAME` + `MAIL_PASSWORD` in `.env`.
2. **Google OAuth2 (Customer 1-Click Sign-In):**
   In Google Cloud Console → **Credentials**, create an **OAuth 2.0 Web Application** client with Authorized JavaScript origin `http://localhost:8080` and Authorized redirect URI `http://localhost:8080/api/v1/auth/google/callback`, then set `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` in `.env`.
3. **Groq AI Vehicle Advisor:**
   Generate a free key at `console.groq.com/keys` and set `GROQ_API_KEY=gsk_...` in `.env`. Note: Groq **only** confirms or steps up vehicle capacity — all INR fares are computed deterministically on the server from MySQL `vehicle_types`.
4. **Razorpay Test Mode:**
   Generate Test Mode API keys (`rzp_test_...`) in the Razorpay Dashboard and paste `RAZORPAY_KEY_ID` and `RAZORPAY_KEY_SECRET` into `.env` (or set `RAZORPAY_SIMULATION_ENABLED=true` for offline local testing).

---

## REST API Overview (`/api/v1`)

Interactive OpenAPI documentation is available at **`/swagger-ui.html`**.

- **System & Public Discovery**
  - `GET /api/v1/health` — Liveness & MySQL pool readiness check
  - `GET /api/v1/vehicle-types` — Active commercial vehicle catalog, dimensions & INR rates
  - `GET /api/v1/maps/geocode?q=...&limit=5` — India-scoped place autocomplete
  - `POST /api/v1/shipments/quote` — Side-effect-free road distance, deterministic INR quote & AI/rule vehicle advice
- **Authentication & Customer Google OAuth2**
  - `POST /api/v1/auth/register/customer` | `POST /api/v1/auth/register/driver` (`multipart/form-data`)
  - `POST /api/v1/auth/login` | `POST /api/v1/auth/refresh` | `POST /api/v1/auth/logout` | `GET /api/v1/auth/me`
  - `POST /api/v1/auth/forgot-password` | `POST /api/v1/auth/reset-password`
  - `GET /api/v1/auth/google/status` | `GET /api/v1/auth/google/authorize` | `GET /api/v1/auth/google/callback` | `POST /api/v1/auth/google/exchange`
- **Shipper (`CUSTOMER`)**
  - `GET /api/v1/customer/me` | `PUT /api/v1/customer/me`
  - `POST /api/v1/shipments` | `GET /api/v1/shipments/my` | `GET /api/v1/shipments/{id}`
  - `GET /api/v1/shipments/{id}/history` | `GET /api/v1/shipments/{id}/tracking` | `POST /api/v1/shipments/{id}/cancel`
- **Payments & Webhooks**
  - `POST /api/v1/payments/orders` | `POST /api/v1/payments/verify` | `POST /api/v1/payments/simulate-checkout`
  - `POST /api/v1/payments/webhook` (raw-payload `X-Razorpay-Signature` HMAC-SHA256 + idempotency key)
  - `GET /api/v1/payments/shipment/{shipmentId}`
- **Driver Partner (`DRIVER`)**
  - `GET /api/v1/driver/me` | `PUT /api/v1/driver/documents/{docType}` | `GET /api/v1/driver/documents/{documentId}`
  - `PATCH /api/v1/driver/availability` | `PUT /api/v1/driver/location`
  - `GET /api/v1/driver/offers/pending` | `POST /api/v1/driver/offers/{offerId}/accept` | `POST /api/v1/driver/offers/{offerId}/decline`
  - `GET /api/v1/driver/shipments/active` | `GET /api/v1/driver/shipments`
  - `POST /api/v1/driver/shipments/{id}/pickup` | `.../in-transit` | `.../deliver`
- **Operations Admin (`ADMIN`)**
  - `GET /api/v1/admin/drivers` | `PATCH /api/v1/admin/drivers/{id}/approve` | `.../reject` | `.../deactivate`
  - `GET /api/v1/admin/shipments` | `GET /api/v1/admin/payments` | `POST /api/v1/admin/payments/{paymentId}/refund`

---

## Running Tests & JaCoCo Coverage

From `backend/`:

```bash
mvn clean verify
```

- Runs unit tests, MockMvc security slice tests, full Spring Boot + MySQL integration flows (auth, refresh rotation, KYC uploads, pricing, Razorpay webhooks/refunds, sequential driver matching, live tracking, HTML email rendering, and mock-server Google OAuth2).
- Generates the HTML coverage report at **`backend/target/site/jacoco/index.html`** (`> 90%` service line coverage).
- Deep-dive architecture notes & 20 viva/placement Q&As: see **[`docs/ARCHITECTURE_AND_INTERVIEW_GUIDE.md`](docs/ARCHITECTURE_AND_INTERVIEW_GUIDE.md)**.

---

## Support & Author

- **Support Email:** [teamapnasamaan@gmail.com](mailto:teamapnasamaan@gmail.com)
- **Built by:** **Shudanshu Bora** — [GitHub](https://github.com/shudanshubora1410) · [LinkedIn](https://linkedin.com/in/shudanshubora1410)

© 2026 Apna Samaan. Built by Shudanshu Bora.
