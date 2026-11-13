# Flutter App / Backend API Workflow Review

**Review date:** 2026-11-10  
**Backend:** Ticket Master Spring Boot API  
**Purpose:** Comparison reference for checking that the Flutter customer app follows the backend's API contract and state transitions.

## 1. Review summary

The backend supports the complete customer purchase lifecycle:

1. Browse events and inspect ticket types.
2. Authenticate as a customer.
3. Place an order, which reserves ticket inventory and starts in `PENDING`.
4. Request a PayChangu hosted checkout for that order.
5. Return from the payment flow and ask the backend to verify with PayChangu.
6. On verified success, the backend changes the order to `PAID` and issues tickets.
7. Fetch and display the issued ticket details and QR content.

The app must treat the backend as authoritative for availability, order totals, payment status, and ticket issuance. A gateway redirect or client-side state is not proof of payment.

## 2. API conventions the app must follow

| Concern | Backend contract |
|---|---|
| Local development base URL | `http://localhost:8080`; use the correct host address for the emulator/device rather than hardcoding localhost in a deployed app. |
| API description | `/v3/api-docs`; Swagger UI at `/swagger-ui.html`. |
| Authentication | Send `Authorization: Bearer <token>` on authenticated requests. JWT authentication is stateless. |
| Successful JSON | DTO property names are camelCase. IDs are numeric; monetary amounts are decimal numbers. |
| Date/time | Local ISO-8601 strings, e.g. `2026-12-05T15:00:00`, with no timezone suffix. Parse as local date-time; do not silently assume UTC. |
| Common error body | `timestamp`, `status`, `error`, `message`, and `path`. Validation errors provide a user-facing `message`. |
| Payment currency | `MWK`. The server calculates the amount from the saved order; client-supplied prices are not authoritative. |
| Media paths | Event `coverImagePath` may be relative (for example `/uploads/events/example.jpg`); resolve it against the configured backend origin. |
| Mobile vs web | Native Flutter apps are not subject to browser CORS enforcement. The current backend CORS configuration allows localhost and 127.0.0.1 origins; Flutter Web production origins need an explicit backend CORS configuration. |

Never put PayChangu credentials or other server secrets in the app. Payment credentials are handled by the backend and the hosted checkout.

## 3. Customer purchase lifecycle

### A. Browse and select an event

| Request | Access | Use |
|---|---|---|
| `GET /api/events?category={category}&search={search}` | Public | Browse events; filters are optional. |
| `GET /api/events/{id}` | Public, except drafts are visible only to their owner | Load event details. |
| `GET /api/events/{eventId}/ticket-types` | Public | Load ticket types if not already included in the event response. |
| `GET /api/ticket-types/{id}` | Public | Load an individual ticket type if needed. |

The event response contains `id`, `title`, `category`, `venue`, `location`, `startTime`, `endTime`, `status`, `availability`, `coverImagePath`, `minPrice`, `capacity`, `remainingTickets`, and `ticketTypes`. Each embedded ticket type contains `id`, `name`, `price`, `totalQuantity`, and `remaining`.

The public list endpoint returns published events only. A listing is not a guarantee that an event is currently purchasable. The app must prevent or clearly disable purchase when availability is not `AVAILABLE`, the event has ended, or a requested ticket type has no remaining inventory. The server independently rechecks eligibility and stock when an order is submitted.

There is no general pagination parameter documented for the event list. Do not invent one. The README describes nearby/recommended discovery, but those routes do not appear in the checked-in OpenAPI file or the inspected event controller. Treat them as unavailable until confirmed against the running server.

### B. Authenticate

| Request | Access | Use |
|---|---|---|
| `POST /api/auth/register` | Public | Register; default role is `CUSTOMER`. |
| `POST /api/auth/login` | Public | Email/password sign-in. |
| `POST /api/auth/google` | Public | Google ID-token sign-in. |
| `POST /api/auth/refresh` | Public | Refresh an access token when supported by the app. |
| `GET /api/customers/me` | Authenticated | Load the current customer profile. |
| `PUT /api/customers/me` | Authenticated | Update the current customer profile. |

Authentication responses contain `token`, `refreshToken`, and `user`. Persist tokens using secure platform storage and never log them. Send the access token on customer/order/payment/ticket endpoints. If the access token expires, use the backend refresh flow or require sign-in; do not retry an unauthorized request indefinitely.

The profile can be used to collect/display customer information. The server falls back to a generic customer name if a usable profile name is unavailable at checkout.

### C. Place a pending order

`POST /api/orders` — authenticated `CUSTOMER`.

Request:

```json
{
  "eventId": 42,
  "items": [
    {
      "ticketTypeId": 101,
      "quantity": 2
    }
  ]
}
```

Successful response (`OrderResponse`):

```json
{
  "id": 501,
  "eventId": 42,
  "totalAmount": 30000.00,
  "status": "PENDING",
  "createdAt": "2026-11-09T13:00:00",
  "items": [
    {
      "ticketTypeId": 101,
      "quantity": 2,
      "unitPrice": 15000.00
    }
  ]
}
```

Backend behavior and app implications:

- The backend requires a published event that has not ended and confirms every ticket type belongs to that event.
- It checks stock under a lock, calculates unit prices and total from stored prices, reserves stock, and creates a `PENDING` order.
- Duplicate ticket-type entries are rejected. Send each selected type once with its selected quantity.
- The default maximum is 10 tickets total per order (`app.orders.max-tickets-per-order` can override it). Validate in the UI for convenience, but still handle backend rejection.
- The app should use the returned `id`, `totalAmount`, `status`, and item prices as authoritative; a displayed cart estimate is only an estimate.
- Prevent duplicate taps/submissions while this request is in flight.
- Pending orders expire after the configured pending period (default 30 minutes). A scheduled backend task marks expired pending orders `CANCELLED` and releases their reserved stock. Do not assume a pending order remains payable indefinitely.

### D. Create PayChangu checkout

`POST /api/payments/checkout` — authenticated `CUSTOMER` who owns the order.

Request:

```json
{
  "orderId": 501
}
```

Successful response (`CheckoutResponse`):

```json
{
  "orderId": 501,
  "txRef": "a9b91d06-846f-4b2b-a5aa-b64cb34c5f8c",
  "checkoutUrl": "https://checkout.paychangu.example/session",
  "amount": 30000.00,
  "currency": "MWK",
  "status": "PENDING"
}
```

The app must:

- Send only the order ID; never send an amount and never calculate/override the backend's payable amount.
- Open the returned `checkoutUrl` using an appropriate hosted browser/payment flow. Do not collect gateway credentials inside the app.
- Retain the associated `orderId` across browser/app switching and app process restoration so the order can be verified afterward. Retain `txRef` as a correlation value, not as proof of payment.
- Handle a checkout error explicitly. The backend prevents a second checkout while a PayChangu payment attempt for that order is still pending; the user should verify the existing attempt before retrying.
- Do not create a replacement order merely because the user returned from the payment page or the app was backgrounded.

### E. Return and verify payment

The backend's default app redirect URI is `myapp://payment-result`, configurable with `APP_PAYMENT_REDIRECT_URI` / `app.payment.app-redirect-uri`. The callback/return handlers verify the transaction and append its `orderId`, verified `status`, and `tx_ref`. The app must use the order ID to call authenticated `/api/payments/verify`; redirect query values are not proof of payment.

`status` is backend callback information and `tx_ref` is for correlation only. Neither query parameter is independently proof of payment. Treat an app link as a signal to resume verification, not as a payment receipt.

`POST /api/payments/verify` — authenticated `CUSTOMER` who owns the order.

Request:

```json
{
  "orderId": 501
}
```

Response shape (`PaidOrderResponse`):

```json
{
  "order": {
    "id": 501,
    "eventId": 42,
    "totalAmount": 30000.00,
    "status": "PAID",
    "createdAt": "2026-11-09T13:00:00",
    "items": []
  },
  "tickets": [],
  "paymentStatus": "PAID"
}
```

The backend directly verifies the saved transaction with PayChangu. It requires the returned reference to match, provider status to be `success`, currency to be `MWK`, and verified amount to be at least the order total. On success it marks the order paid and issues tickets. Failed provider status leaves the order pending according to the endpoint documentation; callback/return handling may report `failed` for an unknown transaction and `pending` when verification cannot be completed.

App state rules:

| Backend result | App behavior |
|---|---|
| `order.status == PAID` and `paymentStatus == PAID` | Show success; display the returned tickets. |
| Payment/order still pending | Show “awaiting confirmation”; allow a deliberate recheck or bounded retry. Do not show a usable ticket as paid. |
| Failure or cancelled order | Show the relevant result and recovery options; do not claim payment succeeded. |
| Network/gateway/server error | Preserve the order ID and show a recoverable error. A failed verification request is not proof that payment failed. |

When verification cannot reach PayChangu, avoid unbounded polling. Offer a manual “Check payment status” action or bounded retries with delay.

### F. Display issued tickets and QR codes

Successful payment verification returns `tickets` in the same response. Each ticket contains ticket/event identifiers and details, `ticketCode`, `qrContent`, status, issue/event times, and cover-image path.

Ticket retrieval endpoints:

| Request | Access | Use |
|---|---|---|
| `GET /api/orders/{id}/tickets` | Authenticated order owner | Tickets belonging to a specific order. |
| `GET /api/tickets/my` | Authenticated | Customer's tickets. |
| `GET /api/tickets/{id}` | Authenticated owner | One ticket. |
| `GET /api/auth/qr/{text}` | Public, image/png | Backend QR image rendering, if desired. |

Generate a QR locally or use the QR endpoint, but encode exactly `qrContent` (the backend currently sets it to the ticket code). Do not encode the order ID, payment reference, or a client-generated replacement. Treat backend ticket status (`VALID`, `CANCELLED`, or used status where applicable) as authoritative.

The backend only issues tickets when an order is marked paid by the verified payment flow (or the dev-only demo payment path). The app must not fabricate or persist ticket records locally as proof of purchase.

### G. Orders, ticket cancellation, and history

| Request | Access | Use / constraint |
|---|---|---|
| `GET /api/orders/my` | `CUSTOMER` | Customer order history. |
| `GET /api/orders/{id}` | `CUSTOMER`, owner only | Refresh an order's current status. |
| `POST /api/orders/{id}/cancel` | `CUSTOMER`, owner only | Cancel only a `PENDING` order; releases reserved stock. |
| `POST /api/tickets/{id}/cancel` | Authenticated owner | Cancel an owned ticket only while it is `VALID`; success is HTTP 204. |

Cancellation of a pending order is not a refund operation and must not be presented as cancelling a completed payment. A paid order cannot be cancelled through the pending-order endpoint. A valid ticket can be cancelled separately, but the app should not promise a refund unless another backend contract explicitly provides one.

## 4. Other backend surfaces (not required for the purchase path)

These are available but should not be confused with customer checkout:

- Interests: `GET /api/interests`, `GET /api/customers/me/interests`, `POST /api/customers/me/interests?interestId={id}`, and `DELETE /api/customers/me/interests/{interestId}`.
- Organizer event management: event creation/edit/publish/cancel, `GET /api/organizer/events`, and organizer-owned event image upload.
- Ticket-type management: create/update ticket types; organizer/admin workflows should follow their actual authorization requirements.
- Validation: `POST /api/tickets/validate` is for a `VALIDATOR`, not a customer ticket-purchase screen.
- Sales report: `GET /api/reports/events/{eventId}/sales`.
- Payment callback/return/webhook are backend/gateway integration surfaces. The app should use the app redirect to resume, then call authenticated `/api/payments/verify`; it should not implement webhook handling.
- `POST /api/dev/orders/{orderId}/mark-paid` is only for the development profile with demo payment enabled. Never use it as the production payment flow.
- `POST /api/orders/{id}/status` does not exist. The app must not attempt to set an order paid or otherwise mutate order state directly.

## 5. Contract consistency findings

The checked-in endpoint guide, OpenAPI snapshot, and implementation are not perfectly aligned. These points should be confirmed when comparing the app against the deployed/running backend:

| Finding | Evidence and impact |
|---|---|
| Nearby/recommended event routes are unconfirmed | The README describes them, but the checked-in OpenAPI route list and inspected `EventController` do not expose them. Do not make them required app dependencies without checking the deployed API. |
| Payment callback, return, and webhook routes are absent from checked-in OpenAPI | They are implemented in `PaymentController` and listed in `ENDPOINTS.md`. The app should use the configured app redirect and `/api/payments/verify`, not rely on the OpenAPI snapshot to discover gateway behavior. |
| OpenAPI snapshot is not the complete runtime contract | Use the endpoint guide and controllers/services together; prefer the running server's `/v3/api-docs` for the current schema and confirm that it matches the deployed version. |
| Event list includes non-purchasable states | The public list now excludes cancelled and draft events; the app should still use event availability and order submission must still handle server-side rejection. |

These are documentation/API-surface alignment issues, not a claim that the purchase flow is broken. Resolve them before requiring optional discovery features or treating the checked-in OpenAPI JSON as exhaustive.

## 6. App-to-backend comparison checklist

Use this checklist when reviewing the Flutter implementation:

- [ ] Event cards and detail screens use server event/ticket-type IDs and handle relative cover image paths.
- [ ] Cancelled, ended, sold-out, and unpublished events cannot proceed to a successful order in the UI; server errors remain handled as final authority.
- [ ] Ticket quantities are positive, duplicate types are consolidated, and total order quantity respects the configured maximum.
- [ ] Checkout is gated behind customer authentication and every protected API request uses the access token.
- [ ] Order confirmation uses the backend response amount and status, not only local cart arithmetic.
- [ ] The order ID survives hosted checkout and app lifecycle transitions.
- [ ] The app opens only the `checkoutUrl` returned by `/api/payments/checkout`.
- [ ] App links match the configured backend redirect scheme/host/path.
- [ ] Redirect `status` and `tx_ref` never directly change app payment state to paid.
- [ ] App calls `/api/payments/verify` for the retained order ID and displays tickets only after an authoritative paid response.
- [ ] Pending, failed, cancelled, timeout, 401, validation, gateway, and connectivity states are distinct and recoverable where appropriate.
- [ ] QR payload is the returned `qrContent`; it is not generated from an order ID or transaction reference.
- [ ] Order/ticket history and ticket cancellation use the customer-owned endpoints and respect server statuses.
- [ ] No app code calls PayChangu APIs directly, embeds secrets, or uses the dev mark-paid endpoint outside explicit development/testing.
- [ ] Tests cover request/response parsing, deep-link resumption, duplicate submission prevention, pending verification, and exact QR payload.

## 7. Integration points to verify with the app/deployment

1. **Deep-link alignment:** Register the same URI scheme in Flutter platform configuration and set the backend `APP_PAYMENT_REDIRECT_URI`. The default is `myapp://payment-result`; changing only one side breaks return handling.
2. **Backend reachability:** `localhost` inside a mobile device refers to that device. Configure an environment-specific base URL for emulator, simulator, physical device, and production.
3. **Flutter Web CORS:** Current CORS origins are localhost and 127.0.0.1 patterns. Add the deployed web origin on the backend if browser-based Flutter is targeted.
4. **Order/payment expiry:** Default pending orders expire after 30 minutes. Ensure the UI handles an expired `CANCELLED` order and offers a fresh order only after refreshing availability.
5. **Status DTOs and OpenAPI:** The review is based on the checked-in endpoint guide and inspected controller/service DTOs. When backend behavior changes, regenerate/check `/v3/api-docs` and update this review and the app models together.

## 8. Backend source references

- [Endpoint guide](./ENDPOINTS.md)
- [Authentication controller](../src/main/java/com/ticketmaster/ticketmaster/user/controller/AuthController.java)
- [Event controller](../src/main/java/com/ticketmaster/ticketmaster/events/EventController.java)
- [Event response](../src/main/java/com/ticketmaster/ticketmaster/events/dto/EventResponse.java)
- [Order controller](../src/main/java/com/ticketmaster/ticketmaster/order/controller/OrderController.java)
- [Order service](../src/main/java/com/ticketmaster/ticketmaster/order/service/OrderService.java)
- [Payment controller](../src/main/java/com/ticketmaster/ticketmaster/controller/PaymentController.java)
- [Payment processing service](../src/main/java/com/ticketmaster/ticketmaster/payment/PaymentProcessingService.java)
- [Ticket controller](../src/main/java/com/ticketmaster/ticketmaster/ticket/TicketController.java)
- [Ticket query service](../src/main/java/com/ticketmaster/ticketmaster/ticket/TicketQueryService.java)
- [Security configuration](../src/main/java/com/ticketmaster/ticketmaster/config/SecurityConfig.java)
