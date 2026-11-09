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
- `POST /api/payments` accepts `{"orderId":123,"amount":25.00,"succeed":true}`. Omit `succeed` for a successful mock payment or set it to `false` to simulate failure. The resulting `PAID`/`FAILED` record is sent to the order service by PATCHing `/api/orders/{orderId}/status` with `{"status":"PAID"}` or `{"status":"FAILED"}`.
- `GET /api/payments/{orderId}` returns the latest payment attempt, including whether the order-status callback succeeded.

Set `ORDER_SERVICE_URL` to Zymal's service base URL; it defaults to `http://localhost:8081`. Payment attempts remain persisted if that callback is unavailable, and the response records the callback error for follow-up.

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