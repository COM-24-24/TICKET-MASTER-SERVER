# Ticket Master — Event Ticketing Backend

Group FF5 mini-project for Software Engineering.

## About

Spring Boot backend for an event ticketing system. Handles event discovery, ticket purchasing, customer profiles, and digital tickets with QR codes.

## Event Discovery and Payments

- Event `LocalDateTime` fields are serialized as ISO-8601 strings without a timezone offset, for example `2026-12-05T15:00:00`.
- `GET /api/events?category=Music&search=stadium` returns published and cancelled events in start-time order. Search matches title, venue, or location without regard to case.
- `GET /api/events/{id}` returns event details, availability, capacity and remaining-ticket totals, minimum price, and `ticketTypes` with price and inventory.
- `GET /api/events/nearby?latitude=...&longitude=...&radiusKm=...` returns events ordered by Haversine distance with `distanceKm` and supports pagination.
- `GET /api/events/recommended?interests=Music&interests=Sports` matches categories provided by the customer-interest source (Beatrice's service).
- `POST /api/payments/checkout` accepts `{"orderId":123}` for the authenticated customer's pending order. The server derives the amount and returns a PayChangu hosted checkout URL, transaction reference, amount, `MWK` currency, and `PENDING` status.
- `POST /api/payments/verify` verifies pending attempts directly with PayChangu before marking the order paid and returning tickets.
- PayChangu redirects to `GET /api/payments/callback` or `GET /api/payments/return`; neither redirect is treated as proof of payment. Optional `POST /api/payments/webhook` notifications are accepted only with a valid raw-body HMAC signature and are re-verified with PayChangu.

## Tech Stack

- Spring Boot 3.3.5
- MariaDB 11.x (local install on port 3306)
- JWT authentication (jjwt 0.12.6)
- Swagger UI for API documentation (springdoc 2.6.0)
- Maven build (via `mvnw`)

## Getting Started

### Prerequisites

- Java 17
- MariaDB 11.x running locally on port 3306
- No Maven install needed — the wrapper (`mvnw`) is included

### Setup

```bash
# Clone the repository
git clone https://github.com/bsc-inf-41-25/ff5_backend.git
cd ff5_backend

# Create the database
mysql -uroot -proot -e "CREATE DATABASE ticketmaster;"

# Run the application
./mvnw spring-boot:run