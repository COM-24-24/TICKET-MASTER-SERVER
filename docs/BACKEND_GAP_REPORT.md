# 0. ENVIRONMENT

| Item | Finding | Evidence |
|---|---|---|
| Java | Java 17 target/source (`java.version`, compiler source/target); current test runtime was Java 26. | `pom.xml` properties/build; Maven test output |
| Spring Boot | 3.3.5 | `pom.xml` parent |
| Build | Maven, Maven Wrapper included (`mvnw.cmd`) | `pom.xml`; top-level repository files |
| Database | MariaDB; `org.mariadb.jdbc:mariadb-java-client` 3.3.3 | `pom.xml`; `application.yml` |
| Schema management | Hibernate `ddl-auto: update`; no Flyway/Liquibase dependency or migration files identified | `application.yml`; `pom.xml` |
| Server | `0.0.0.0:8080`; no context path configured | `application.yml` |
| Profiles | No named Spring profiles or profile-specific `application-*.yml` files found | `src/main/resources/` |
| OpenAPI | `/v3/api-docs/**`; Swagger UI `/swagger-ui.html` and `/swagger-ui/**` | `config\SecurityConfig.securityFilterChain`; `application.yml` |
| OpenAPI capture | Existing listener returned HTTP 200 with valid JSON from `http://localhost:8080/v3/api-docs`; checked against configured secret literals and saved as `docs/api-docs.json`. The listening process was named `httpd`, so this confirms the endpoint response, not a fresh source launch. | Live request; `docs\api-docs.json` |
| Start, plain | README documents `./mvnw spring-boot:run` (Windows: `.\mvnw.cmd spring-boot:run`); `TicketmasterApplication.main` starts Spring. Requires a reachable MariaDB database. | `README.md`; `TicketmasterApplication.main`; `application.yml` |
| Start, Docker | `docker compose up` runs the MariaDB service and the prebuilt backend image; there is no Dockerfile in the repository. | `docker-compose.yml`; no `Dockerfile` exists |
| `mvn test` | **FAIL.** `mvn` executable is not installed; ran `.\mvnw.cmd test` once: 25 tests, 0 assertion failures, 17 errors. First underlying exception: Byte Buddy does not support Java 26 class-file version 70 (the current bundled version supports through Java 23/67). `TicketmasterApplicationTests` also errors with `ClassNotFoundException: PayChanguService`. | `pom.xml`; Maven Wrapper output; `target\surefire-reports\*` |

# 1. ENDPOINT INVENTORY

`permitAll` means the matching SecurityConfig rule does not require a token; controller/service checks noted separately still apply. Response fields below reflect the declared DTO/entity or direct return type. "Framework/default; exact body UNKNOWN" means there is no endpoint-specific response contract in source.

| # | HTTP method | FULL path | Controller.method | Access in practice | Required role | Request | Response | Error responses |
|---:|---|---|---|---|---|---|---|---|
| 1 | POST | `/api/auth/register` | `AuthController.register` | Public (`/api/auth/**`) | None | `RegisterRequest`: `email:String @Email @NotBlank`, `password:String @NotBlank @Size(min=6)`, `role:User.Role @NotNull` | 200 `AuthResponse {token:String, refreshToken:String}` | Invalid body 400; duplicate email is `IllegalArgumentException`->400, not 409 |
| 2 | POST | `/api/auth/login` | `AuthController.login` | Public | None | `LoginRequest`: `email:String @Email @NotBlank`, `password:String @NotBlank` | 200 `AuthResponse {token, refreshToken}` | Validation 400; bad-credential status/body not explicitly handled; exact result UNKNOWN |
| 3 | POST | `/api/auth/google` | `AuthController.googleLogin` | Public | None | `GoogleLoginRequest {idToken:String @NotBlank}` | 200 `AuthResponse {token, refreshToken}` | Invalid token is `IllegalArgumentException`->400; validation 400 |
| 4 | POST | `/api/auth/refresh` | `AuthController.refreshToken` | Public | None | `RefreshTokenRequest {refreshToken:String}` (no validation) | 200 `AuthResponse {token, refreshToken}` | Explicit invalid-token branch throws `IllegalArgumentException`->400; JWT parse exceptions have UNKNOWN status/body |
| 5 | POST | `/api/events` | `EventController.createEvent` | Matcher is public; controller rejects anonymous callers | Any authenticated user; no role check | `Event` JSON: `organizerId:Long`, `title:String`, `venue:String`, `location:String`, `dateTime/startTime/endTime:LocalDateTime`, `status:EventStatus`, `organizerUsername:String`; no `@Valid` | 201 `Event` entity | Anonymous 401; invalid dates 400; other body/persistence errors framework/default |
| 6 | GET | `/api/events` | `EventController.getAllEvents` | Public | None | None | `List<Event>`; includes all statuses | Framework/default |
| 7 | GET | `/api/events/{id}` | `EventController.getEventById` | Public | None | `id:Long` path | `Event` entity | Missing event uses 404 `ResponseStatusException`; exact body UNKNOWN |
| 8 | PUT | `/api/events/{id}` | `EventController.editEvent` | Matcher public; controller requires authentication | Any authenticated user; no role check | `id:Long`; body `Event` | `Event` entity | Anonymous 401; missing event 404; non-draft 400; invalid time 400 |
| 9 | PATCH | `/api/events/{id}/publish` | `EventController.publishEvent` | Matcher public; controller requires authentication | Any authenticated user; no role check | `id:Long` | `Event` entity | Anonymous 401; missing event 404; non-draft/bad time 400 |
| 10 | PATCH | `/api/events/{id}/cancel` | `EventController.cancelEvent` | Matcher public; controller requires authentication | Any authenticated user; no role check | `id:Long` | `Event` entity | Anonymous 401; missing event 404; already cancelled 400 |
| 11 | GET | `/api/organizer/events` | `OrganizerEventController.myEvents` | Authenticated by `hasRole("ORGANIZER")` | ORGANIZER | None | `List<Event>` for principal username | Non-organizer 403; response body framework/default |
| 12 | POST | `/api/events/{eventId}/ticket-types` | `TicketTypeController.create` | Matcher public; method checks for authenticated user | Any authenticated user; no role/ownership check | `eventId:Long`; body `CreateTicketTypeRequest {name:String, price:BigDecimal, totalQuantity:Integer}` (no validation annotations) | 201 `TicketType` entity, including `remaining` getter and nested `event` | Anonymous 401; unknown event 404; bad price/capacity 400 |
| 13 | PUT | `/api/ticket-types/{id}` | `TicketTypeController.update` | Matcher public; method checks for authenticated user | Any authenticated user; no role/ownership check | `id:Long`; same request; `name` is ignored | `TicketType` entity | Anonymous 401; unknown type 404; bad price/capacity 400 |
| 14 | GET | `/api/events/{eventId}/ticket-types` | `TicketTypeController.list` | Public | None | `eventId:Long`; optional `name:String` | `List<TicketType>`; exact case-insensitive name filter | No explicit unknown-event error; empty list possible |
| 15 | GET | `/api/ticket-types/{id}` | `TicketTypeController.getOne` | Public | None | `id:Long` | `TicketType`; 404 with empty body if missing | 404 |
| 16 | POST | `/api/orders` | `OrderController.placeOrder` | `hasRole("CUSTOMER")` | CUSTOMER | `PlaceOrderRequest {eventId:Long @NotNull, items:List @NotEmpty}`; each item `{ticketTypeId:Long @NotNull, quantity:Integer @NotNull @Min(1)}` | `OrderResponse {id,eventId,totalAmount:BigDecimal,status:String,createdAt:LocalDateTime,items:List<OrderItemResponse>}`; item `{ticketTypeId,quantity,unitPrice}` | Validation 400; unavailable type/stock 400; wrong role 403 |
| 17 | GET | `/api/orders/my` | `OrderController.getMyOrders` | `hasRole("CUSTOMER")` | CUSTOMER | None | `List<OrderResponse>` | Wrong role 403 |
| 18 | GET | `/api/orders/{id}` | `OrderController.getOrderById` | `hasRole("CUSTOMER")`; service checks owner email | CUSTOMER and owner | `id:Long` | `OrderResponse` | Missing order `IllegalArgumentException`->400; not owner `SecurityException`->403 |
| 19 | POST | `/api/orders/{id}/cancel` | `OrderController.cancelOrder` | `hasRole("CUSTOMER")`; service checks owner and PENDING | CUSTOMER and owner | `id:Long` | `OrderResponse` | Missing order 400; not owner 403; non-pending 409 |
| 20 | POST | `/api/orders/{id}/status` | `OrderController.updateStatus` | Explicit `permitAll` matcher precedes customer matcher | None in practice | `id:Long`; body `Map<String,String> {status:String}` | Empty 200 response | Unknown status becomes `IllegalArgumentException`->400; order errors 400/409; accessible without token |
| 21 | POST | `/api/payments/checkout` | `PaymentController.checkout` | Public (`/api/payments/**`) | None | `Map<String,Object>`; reads `amount` converted to `Double` and `orderId` to `Long`; no validation | `Map {checkoutUrl:String}`; does not return `tx_ref` | Number parsing `IllegalArgumentException`->400; gateway runtime errors are unhandled; exact body UNKNOWN |
| 22 | GET | `/api/payments/callback` | `PaymentController.callback` | Public | None | Required query `tx_ref:String` | 302 redirect to `myapp://payment-result?...`; no JSON | Missing parameter 400; redirect behavior only |
| 23 | GET | `/api/payments/return` | `PaymentController.returnUrl` | Public | None | Optional `tx_ref:String`; `status:String` defaults to `"failed"` | 302 redirect to `myapp://payment-result?...` | Missing query accepted; exact malformed redirect behavior UNKNOWN |
| 24 | GET | `/api/customers/me` | `CustomerProfileController.getMyProfile` | Matcher public; service falls back to user id 1 when anonymous or principal is nonnumeric | None in practice | None | `CustomerProfileResponse {id,userId,fullName,phone,city,latitude,longitude}` | Missing profile throws `IllegalStateException`->409; fallback may expose user 1's profile |
| 25 | PUT | `/api/customers/me` | `CustomerProfileController.updateMyProfile` | Matcher public; same user-id fallback | None in practice | `CustomerProfileRequest {fullName:String @NotBlank @Size(max=120), phone:String @Size(max=30), city:String @Size(max=120), latitude:Double [-90,90], longitude:Double [-180,180]}` | `CustomerProfileResponse` | Validation 400; unauthenticated request can write profile for user id 1 |
| 26 | GET | `/api/customers/me/interests` | `CustomerInterestController.getMyInterests` | Matcher public; controller resolves user and requires CUSTOMER | CUSTOMER (manual check) | Security principal | `List<CustomerInterest>` | Missing user 401; wrong role 403 |
| 27 | POST | `/api/customers/me/interests` | `CustomerInterestController.addInterest` | Matcher public; manual CUSTOMER check | CUSTOMER | Query `interestId:Long` | `CustomerInterest` entity | Missing interest/duplicate 400; missing user 401; wrong role 403 |
| 28 | DELETE | `/api/customers/me/interests/{interestId}` | `CustomerInterestController.removeInterest` | Matcher public; manual CUSTOMER check | CUSTOMER | `interestId:Long` path | Empty response | Missing selection 400; missing user 401; wrong role 403 |
| 29 | GET | `/api/tickets/my` | `TicketController.getMyTickets` | Authenticated by catch-all; owner derived from email | Any authenticated user; own tickets | Security principal | `List<TicketResponse>` | Missing authenticated user record throws `IllegalStateException`->409; unauthenticated 401 |
| 30 | GET | `/api/tickets/{id}` | `TicketController.getTicketById` | Authenticated; query filters ticket by owner id | Any authenticated user; own ticket | `id:Long` | `TicketResponse` | Not found/not owner 403 |
| 31 | POST | `/api/tickets/{id}/cancel` | `TicketController.cancelMyTicket` | Authenticated; owner-only service query | Any authenticated user; own ticket | `id:Long` | 204 empty | Not owner 403; used/already cancelled 409 |
| 32 | POST | `/api/tickets/validate` | `TicketValidationController.validateTicket` | `hasRole("VALIDATOR")` | VALIDATOR | `TicketValidationRequest {ticketCode:String @NotBlank}` | `TicketValidationResponse {ticketId,ticketCode,eventTitle,status:TicketStatus,usedAt,message}` | Invalid body 400; unknown ticket 400; already used/cancelled 409 |
| 33 | GET | `/api/auth/qr/{text}` | `QrCodeController.generate` | Public (`/api/auth/**`) | None | `text:String` path | PNG bytes (`image/png`) | Blank text `IllegalArgumentException`->400; encoding `IllegalStateException`->409 |
| 34 | GET | `/api/interests` | `InterestController.getAllInterests` | Public | None | None | `List<Interest> {id,name}` | Framework/default |
| 35 | GET | `/api/reports/events/{eventId}/sales` | `SalesReportController.getSalesReport` | Public (`/api/reports/**`) | None | `eventId:Long` | `List<SalesReportItem> {ticketTypeId,ticketsSold,revenue:BigDecimal}` | Invalid id `IllegalArgumentException`->400 |

**Incomplete, duplicated, or odd routes**
- `PaymentController` exposes only `/checkout`, `/callback`, and `/return`; the callback is a browser redirect, not a payment webhook. `PaymentService.createPayment`/`CreatePaymentRequest` have no mapped endpoint, and `PaymentController.checkout` does not use `PaymentService`. (`payment\PaymentController`; `service\PaymentService.createPayment`; `dto\CreatePaymentRequest`)
- README advertises `/api/events/search`, `/nearby`, `/recommended`, `POST /api/payments`, `GET /api/payments/{orderId}`, and attendance reporting; none is a mapped controller method in compiled source. The attendance controller/service exist only as `.bak` files. (`README.md`; `report\AttendanceReportController.java.bak`)
- `GET /api/events` is an unfiltered `findAll`; `/api/auth/qr/{text}` is an image utility nested under auth; `/api/orders/{id}/status` is a public state mutation. (`events\EventController.getAllEvents`; `qr\QrCodeController.generate`; `order\OrderController.updateStatus`)

# 2. AUTHENTICATION AND USERS

- `User` table `users`: `id:Long`, `email:String` (unique, non-null), `password:String` (non-null), `role:User.Role` (enum string, non-null), `createdAt:LocalDateTime` (non-null; `@PrePersist`). There is no name/fullName field. (`user\entity\User`)
- Roles are one enum value per user: `CUSTOMER`, `ORGANIZER`, `VALIDATOR`. `CustomUserDetailsService.loadUserByUsername` converts one to exactly `ROLE_<ENUM>`; there is no role table or multi-role collection. (`User.Role`; `security\CustomUserDetailsService.loadUserByUsername`)
- `SecurityConfig.passwordEncoder` returns BCrypt. Registration encodes passwords before save. (`config\SecurityConfig.passwordEncoder`; `user\UserService.register`)
- Register accepts only email, password, and required caller-selected role. It accepts neither `name` nor `fullName`; callers can request ORGANIZER or VALIDATOR. Register and login return only `{token, refreshToken}`, no user object. (`RegisterRequest`; `AuthResponse`; `UserService.register/login`)
- Access JWT: `sub=email`, `role=ROLE_*`, issued-at and configured expiration (86,400,000 ms = 24 h). Refresh JWT: `sub=email`, issued-at and fixed 7-day expiration, no role claim. Both use `jwt.secret`. Filter expects `Authorization: Bearer <JWT>`; it loads current authorities from the DB, not the token role claim. (`security\JwtService.generateToken/generateRefreshToken`; `JwtAuthenticationFilter.doFilterInternal`)
- Current-user resolution is inconsistent: most user/ticket/order logic uses the SecurityContext principal name (email); profile logic parses that as a numeric ID, then falls back to ID 1. Customer-interest/ticket controllers resolve email through `UserRepository`. (`CustomerProfileService.currentUserId`; `OrderService`; `CustomerInterestController.getCurrentCustomerId`; `TicketController.getCurrentUserId`)
- Duplicate email is prechecked with `existsByEmail` then throws `IllegalArgumentException("Email already registered")`; global handler maps that to 400. DB column is unique, but concurrent duplicate handling is not translated to 409. (`UserService.register`; `User.email`; `GlobalExceptionHandler.handleBadRequest`)
- Google login verifies audience using `google.client-id`, creates missing users as CUSTOMER with generated random encoded password; it returns same token-only response. (`UserService.googleLogin`)
- Profile GET/PUT: `/api/customers/me`; request fields `fullName, phone, city, latitude, longitude`; response adds `id,userId`. No user registration listener creates profiles: `UserRegisteredEvent` is published, but no `@EventListener` for it was found. (`CustomerProfileController`; `CustomerProfileRequest/Response`; `UserService.register`; `UserRegisteredEvent`)
- No user/customer/organizer/validator seed is configured. Only interests are seeded. (`InterestDataSeeder.run`; `InterestService.seedInterests`)

# 3. ENTITIES AND ENUMS

| Entity/table | All declared fields and Java types | Relations / constraints | Status values and code transitions |
|---|---|---|---|
| `User` / `users` | `id:Long`, `email:String`, `password:String`, `role:Role`, `createdAt:LocalDateTime` | `email` unique/non-null; no JPA relations | `Role={CUSTOMER,ORGANIZER,VALIDATOR}`; caller supplies during register; Google-created user is CUSTOMER. (`User`; `UserService`) |
| `CustomerProfile` / `customer_profiles` | `id:Long`, `userId:Long`, `fullName:String`, `phone:String`, `city:String`, `latitude:Double`, `longitude:Double` | `userId` unique/non-null scalar, no `User` relation | No status. Created/updated by profile PUT or `createForUser`; registration hook does not invoke it. (`CustomerProfile`; `CustomerProfileService`) |
| `Event` / `events` | `id:Long`, `organizerId:Long`, `title:String`, `venue:String`, `location:String`, `dateTime:LocalDateTime`, `startTime:LocalDateTime`, `endTime:LocalDateTime`, `status:EventStatus`, `organizerUsername:String` | Organizer values are scalars, no User relation; required columns include title/venue/location/date times/status/username | `EventStatus={DRAFT,PUBLISHED,CANCELLED}`; create forces DRAFT; publish only DRAFT->PUBLISHED; cancel any non-cancelled state->CANCELLED. (`Event`; `EventService.createEvent/publishEvent/cancelEvent`) |
| `TicketType` / `ticket_types` | `id:Long`, `event:Event`, `name:String`, `price:BigDecimal`, `totalQuantity:Integer`, `quantitySold:Integer` | Required `ManyToOne` Event; no unique constraint; computed getter `remaining=totalQuantity-quantitySold` | No enum. QuantitySold incremented in order placement, decremented on FAILED/CANCELLED status or by unused `CapacityService.releaseCapacity`. (`TicketType`; `OrderService.placeOrder/updateOrderStatus`; `CapacityService`) |
| `Order` / `orders` | `id:Long`, `user:User`, `eventId:Long`, `totalAmount:BigDecimal`, `status:OrderStatus`, `items:List<OrderItem>`, `createdAt:LocalDateTime` | Required `ManyToOne` User; cascade-all `OneToMany` items; eventId scalar, no Event relation | `OrderStatus={PENDING,PAID,FAILED,CANCELLED}`; created PENDING; update status handles PAID/FAILED/CANCELLED; no expiry transition. (`Order`; `OrderService`) |
| `OrderItem` / `order_items` | `id:Long`, `order:Order`, `ticketTypeId:Long`, `quantity:Integer`, `unitPrice:BigDecimal` | Required `ManyToOne` Order; ticketTypeId scalar, no TicketType relation | None. Created by `OrderService.placeOrder` using stored ticket price. |
| `Payment` / `payments` | `id:Long`, `orderId:Long`, `amount:Double`, `status:PaymentStatus`, `createdAt:LocalDateTime`, `orderStatusUpdated:boolean`, `orderStatusUpdateError:String` | Order id scalar; index on `(order_id, created_at)`; no unique reference/order constraint or relation | `PaymentStatus={PAID,FAILED}`; `PaymentService.createPayment` chooses from `succeed` (default true), then calls order status client. No controller invokes this method. |
| `Ticket` / `tickets` | `id:Long`, `orderId:Long`, `event:Event`, `ticketType:TicketType`, `ownerId:Long`, `ticketCode:String`, `status:TicketStatus`, `issuedAt:LocalDateTime`, `usedAt:LocalDateTime` | Event/TicketType required relations; unique ticket_code; orderId/ownerId scalar | `TicketStatus={VALID,USED,CANCELLED}`; issuance sets VALID; validation VALID->USED and sets usedAt; owner cancellation VALID->CANCELLED. (`TicketIssuingService`; `TicketValidationService`; `TicketCancellationService`) |
| `Interest` / `interests` | `id:Long`, `name:String` | `name` unique/non-null | No status. |
| `CustomerInterest` / `customer_interests` | `id:Long`, `customerId:Long`, `interest:Interest` | Interest `ManyToOne`; unique `(customer_id,interest_id)`; customerId scalar | No status. |

`AttendanceReportResponse` is a record, not a persisted entity; attendance `.java.bak` code is not compiled. (`report\AttendanceReportResponse`; `report\AttendanceReportController.java.bak`)

# 4. EVENTS

- List route is only `GET /api/events` and accepts no category, search, date, status, page, or size parameters; `EventService.getAllEvents` calls `findAll`. The separate ticket type list has optional `name`, not an event filter. (`EventController.getAllEvents`; `EventService.getAllEvents`; `TicketTypeController.list`)
- Public list/detail expose all stored event statuses: no status filter or published-only check. New events start DRAFT; publish/cancel operations change state but do not affect list visibility. (`EventService.getAllEvents/getEventById/createEvent/publishEvent/cancelEvent`)
- Event fields include title, venue, location, organizerId, dateTime/startTime/endTime, status, organizerUsername. No category, cover image/image path, event capacity, or base price field exists. Prices and quantities are on ticket types. (`entity\Event`)
- `remaining` on a ticket type is computed as `totalQuantity - quantitySold`; there is no event-wide remaining count or minimum-price aggregation. Ticket types are linked to Event through `ManyToOne`; each has `name`, `price:BigDecimal`, `totalQuantity`, `quantitySold`. (`entity\TicketType`)
- No image upload endpoint, `MultipartFile` handling, upload directory configuration, or static upload serving was found. (`TicketTypeController`; source-wide route/resource search)
- Date fields use `LocalDateTime`, so the type carries no timezone/offset. No per-field JSON format annotation or custom date serializer is configured; exact serialized date format is **UNKNOWN**. (`entity\Event`; `application.yml`)
- Create sets organizerUsername from principal but does not set/validate organizerId. Ownership checks in `EventService.checkOrganizer` are commented out; create/edit/publish/cancel routes match public `/api/events/**`, with manual authentication only on mutations. (`EventService.createEvent/checkOrganizer`; `EventController`; `SecurityConfig.securityFilterChain`)

# 5. ORDER AND INVENTORY FLOW

1. `POST /api/orders` is role-matched CUSTOMER. `OrderController.placeOrder` applies `@Valid`; request requires eventId and at least one item, each item a ticketTypeId and quantity >=1. (`OrderController.placeOrder`; `PlaceOrderRequest`)
2. `OrderService.placeOrder` reads principal email, loads that User, creates PENDING order, locks each ticket-type row via `TicketTypeRepository.findByIdForUpdate`, checks available stock and increments `quantitySold`. `@Transactional` covers stock and order persistence. (`OrderService.placeOrder`; `TicketTypeRepository.findByIdForUpdate`)
3. Price is loaded from each database `TicketType.price`; client cannot submit price. Service supports multiple ticket types/items and any positive quantity; there is no per-order maximum. It does not check that ticket types belong to request `eventId`, nor that the event exists or is PUBLISHED. (`OrderService.placeOrder`)
4. It saves the order and returns `OrderResponse`; no payment is initiated by place-order. Tickets are issued only when `updateOrderStatus(..., PAID)` calls `TicketIssuingService.issueTickets`. (`OrderService.placeOrder/updateOrderStatus`; `TicketIssuingService.issueTickets`)
5. On FAILED or CANCELLED status, `updateOrderStatus` subtracts each item quantity from ticket type, clamped at zero. Customer cancel requires owner and PENDING; controller exposes it at `POST /api/orders/{id}/cancel`. No expiration job/scheduler or pending-order timeout was found. (`OrderService.updateOrderStatus/cancelOrder`)
6. Stock reservation uses pessimistic write locks in a transaction, which protects the reservation check/update against concurrent reservations for a row. `CapacityService` also locks rows but is not called by OrderService. (`TicketTypeRepository.findByIdForUpdate`; `OrderService.placeOrder`; `CapacityService`)
- Ownership: `GET /api/orders/{id}` and cancellation compare order user email with current principal email; `/my` queries by authenticated user id. User B cannot read/cancel A's order through those service methods. (`OrderService.getOrderById/cancelOrder/getMyOrders`)
- Critical bypass: `POST /api/orders/{id}/status` is `permitAll`; anyone can request PAID and cause ticket issue, or FAILED/CANCELLED and release stock. No gateway verification or owner check precedes the state transition. (`SecurityConfig.securityFilterChain`; `OrderController.updateStatus`; `OrderService.updateOrderStatus`)
- Concurrency/integrity gaps: `existsByOrderId` followed by ticket issuance is not protected by an order-level lock or unique order constraint; concurrent PAID calls can both pass the existence check. Stock releases are repeatable only until status reaches a terminal state, but status writes themselves do not lock the Order. No expiry releases stock. (`TicketIssuingService.issueTickets`; `TicketRepository`; `OrderService.updateOrderStatus`; `Order`)
- Further inconsistencies: one order has one scalar eventId but no check ticket-type event matches; event cancellation does not cancel/refund orders or release inventory; checkout accepts its own amount/orderId, unrelated to saved order total. (`OrderService.placeOrder`; `EventService.cancelEvent`; `PaymentController.checkout`)

# 6. PAYMENT FLOW

1. App calls public `POST /api/payments/checkout` with map keys `amount` and `orderId`. Controller converts these caller-controlled values to `Double`/`Long`, uses the SecurityContext name as email, and calls `PayChanguService.initiateCheckout`. There is no order existence, owner, currency/amount-against-order, or pending-status check. (`PaymentController.checkout`)
2. Outgoing request: HTTP **POST** to `${paychangu.base-url}/payment` (configured base URL is PayChangu's API host). Headers: `Content-Type: application/json`, `Authorization: Bearer <configured secret>` (secret value omitted). JSON fields: `amount` as String, `currency:"MWK"`, `email`, fixed `first_name`/`last_name`, generated UUID `tx_ref`, `callback_url`, `return_url`, `customization:{title,description}`, `meta:{order_id}`. (`PayChanguService.initiateCheckout`)
3. Reads `response.data.checkout_url`; controller response is exactly `{"checkoutUrl":"..."}`. Generated `tx_ref` is neither returned nor saved by this path. (`PayChanguService.initiateCheckout`; `PaymentController.checkout`; `Payment`)
4. `GET /api/payments/callback?tx_ref=...` redirects to `myapp://payment-result?status=success&tx_ref=...`; `GET /return` redirects using query status (default `failed`). Both are public browser redirects, not signed webhook handlers; neither verifies with PayChangu or updates payment/order state. (`PaymentController.callback/returnUrl`; `SecurityConfig.securityFilterChain`)
5. There is no verify-payment route or gateway verification call; no checks of returned tx_ref, status, currency, or amount. No idempotency key/reference persistence. Payment checkout errors are caught broadly by PayChanguService and rethrown as RuntimeException; no explicit timeout is configured in source. (`PayChanguService.initiateCheckout`; `Payment`)
- Separate dormant simulation path: `PaymentService.createPayment(CreatePaymentRequest)` persists a PAID/FAILED Payment depending on optional `succeed` (defaults true), then calls `OrderStatusClient`; no controller mapping calls `createPayment`. The `PaymentController` injects `PaymentService` but doesn't use it. It is not a usable demo endpoint. (`service\PaymentService.createPayment`; `dto\CreatePaymentRequest`; `payment\PaymentController`)
- Outgoing order-status request from the dormant path: HTTP POST `/api/orders/{id}/status`, JSON `{status:"PAID"|"FAILED"}`, base URL property `services.order.base-url` defaulting to localhost port 8080. RestClient has no explicit timeout setup in source. `RestClientException` is stored in Payment's update-error fields; payment remains persisted. (`RestOrderStatusClient.updateOrderStatus`; `PaymentService.createPayment`; `Payment`)
- Callback/return config property names are `paychangu.callback-url` and `paychangu.return-url`; defaults point to localhost routes. Other properties: `paychangu.secret-key` (secret), `paychangu.base-url`. (`application.yml`; `PayChanguService`)

# 7. TICKETS AND QR

- Tickets are issued on order status transition to PAID, one per purchased unit, with fields `orderId`, `ownerId`, Event, TicketType, UUID ticketCode, status VALID, and issuedAt. (`OrderService.updateOrderStatus`; `TicketIssuingService.issueTickets`)
- UUID string ticket code; unique constraint on `ticket_code`. Ticket response includes ticket/event/type details and issued/used timestamps, but no buyer name/email, payment reference, order purchase date beyond ticket issue time, or QR content field. (`TicketIssuingService.issueTickets`; `Ticket`; `TicketResponse`)
- QR endpoint is `GET /api/auth/qr/{text}`; ZXing `QRCodeWriter` encodes exactly the supplied path variable text, UTF-8, as 300x300 PNG. The API returns PNG bytes, not a QR content string. There is no signature/hash and no backend link that automatically encodes a ticket's code. (`QrCodeController.generate`; `QrCodeService.generateQrCode`; `pom.xml`)
- Scan endpoint `POST /api/tickets/validate`, role VALIDATOR. It trims code, locks ticket row, accepts only VALID, sets USED and usedAt, and returns ticketId/code/eventTitle/status/time/message. Row lock plus status check serializes concurrent scans so a later scan observes USED. (`TicketValidationController.validateTicket`; `TicketValidationService.validateTicket`; `TicketRepository.findByTicketCodeForUpdate`; `SecurityConfig`)
- Customer endpoints: `GET /api/tickets/my`, `GET /api/tickets/{id}`, `GET /api/orders/my`, `GET /api/orders/{id}`. Tickets and orders are retrieved owner-filtered in services; order detail/cancel explicitly compare email. (`TicketController`; `TicketQueryService`; `OrderController`; `OrderService`)
- No public key, signed QR payload, offline manifest, or offline verification support was found. (`QrCodeService`; `TicketValidationService`)

# 8. SECURITY CONFIGURATION

- Matcher order in `SecurityConfig.securityFilterChain`:
  1. `OPTIONS /**` -> permitAll.
  2. `/error`, `/api/auth/**`, `/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**`, `/api/payments/**`, `/api/orders/*/status`, `/api/events/**`, `/api/ticket-types/**`, `/api/interests/**`, `/api/customers/**`, `/api/reports/**` -> permitAll.
  3. `/api/orders/**` -> `hasRole("CUSTOMER")`.
  4. `/api/organizer/**` -> `hasRole("ORGANIZER")`.
  5. `/api/tickets/validate` -> `hasRole("VALIDATOR")`.
  6. Everything else -> authenticated.
  Because rules are ordered, `/api/orders/*/status` bypasses customer-only rule. `permitAll` on business paths does not mean controller manual checks are absent; see below. (`config\SecurityConfig.securityFilterChain`)
- CSRF disabled; CORS allows every origin, method, and header (`*`); no credentials allowance configured. Stateless sessions. JWT filter runs before username/password filter. (`SecurityConfig.securityFilterChain/corsConfigurationSource`)
- `JwtAuthenticationFilter.doFilterInternal` accepts only `Authorization: Bearer `, validates JWT and sets SecurityContext. It catches all exceptions, logs a warning, and continues as anonymous; invalid JWT is not surfaced directly. (`security\JwtAuthenticationFilter.doFilterInternal`)
- Commented/bypassed checks: `EventService.java` `checkOrganizer` commented body at lines 92-102; `TicketTypeService.java` create ownership check lines 29-35 and update check lines 54-60; profile DEMO fallback to id 1 at lines 24-35. They are not effective authorization checks. (`EventService.checkOrganizer`; `TicketTypeService.create/update`; `CustomerProfileService.currentUserId`)
- With the public business path matchers removed, order methods other than status would be CUSTOMER-only, organizer list ORGANIZER-only, validation VALIDATOR-only, while event and ticket-type methods would fall to authenticated unless more role matchers are added. Currently public business paths include all `/api/events/**`, `/api/ticket-types/**`, `/api/payments/**`, `/api/customers/**`, `/api/reports/**`, and interests. Mutating events/ticket types have manual login checks, not role/ownership checks. (`SecurityConfig`; `EventController`; `TicketTypeController`)

# 9. CONFIGURATION, DOCKER AND DATA

| Property | Configuration / exposure | Evidence |
|---|---|---|
| `spring.application.name` | application name | `application.yml` |
| `spring.datasource.url`, `spring.datasource.username` | Configured in YAML; Compose overrides via `SPRING_DATASOURCE_URL/USERNAME` | `application.yml`; `docker-compose.yml` |
| `spring.datasource.password` | **Hard-coded secret** in YAML; Compose also supplies a credential value | `application.yml`; `docker-compose.yml` |
| `spring.datasource.driver-class-name` | MariaDB driver; Compose overrides `SPRING_DATASOURCE_DRIVER_CLASS_NAME` | `application.yml`; `docker-compose.yml` |
| `spring.jpa.hibernate.ddl-auto`, `spring.jpa.show-sql`, `spring.jpa.properties.hibernate.format_sql` | `update`, SQL logging enabled, formatted SQL | `application.yml` |
| `server.address`, `server.port` | all interfaces; port 8080 | `application.yml` |
| `jwt.secret` | **Hard-coded secret** in YAML; can be overridden by standard `JWT_SECRET` environment binding | `application.yml` |
| `jwt.expiration` | 86,400,000 ms; environment-bindable as `JWT_EXPIRATION` | `application.yml` |
| `google.client-id`, `google.client-secret` | `${GOOGLE_CLIENT_ID}`, `${GOOGLE_CLIENT_SECRET}`; secret property is externally supplied; Compose passes both | `application.yml`; `docker-compose.yml` |
| `paychangu.secret-key` | `${PAYCHANGU_SECRET_KEY}`; external secret; Compose passes it | `application.yml`; `docker-compose.yml` |
| `paychangu.base-url` | configured API host; standard `PAYCHANGU_BASE_URL` binding available | `application.yml`; `PayChanguService` |
| `paychangu.callback-url`, `paychangu.return-url` | `${PAYCHANGU_CALLBACK_URL:...}` and `${PAYCHANGU_RETURN_URL:...}` defaults; env-overridable | `application.yml` |
| `springdoc.swagger-ui.path` | `/swagger-ui.html` | `application.yml` |
| `logging.level.org.springframework.security` | DEBUG | `application.yml` |
| `services.order.base-url` | Not in YAML; `RestOrderStatusClient` uses property with localhost:8080 default; Compose does not set it | `RestOrderStatusClient` |

- Only `application.yml` exists; no `.env.example`, migration scripts, `data.sql`, Dockerfile, or profile-specific config found. `.env` exists locally but was not read and is not an example template. (resource/top-level file inventory)
- `docker-compose.yml`: `mariadb` service uses MariaDB 11.8; port 3306; named `ticketmaster_data` volume; healthcheck; `backend` uses prebuilt `zymal/ticketmaster-backend:latest`, maps port 8080, depends on DB health. No backend healthcheck, no `build`, no `env_file`; Compose interpolates Google and PayChangu environment variables. `docker-compose.yml.backup` is older MariaDB-only config. (`docker-compose.yml`; `docker-compose.yml.backup`)
- Upload-folder handling: none found. No image upload or static-file configuration. (source/resource inventory)
- Initial data: `InterestDataSeeder` calls `InterestService.seedInterests`, idempotently adding Music, Sports, Tech, Community, Food, Art, Fitness. No user, event, ticket type, or validator seeds. On an empty DB Hibernate `update` creates mapped tables and the runner adds those interests; database connection must first work. (`InterestDataSeeder.run`; `InterestService.seedInterests`; `application.yml`)

# 10. ERROR HANDLING

- `GlobalExceptionHandler` returns `{timestamp:String,status:int,error:String,message:String}` for `IllegalArgumentException`->400, `SecurityException`->403, `IllegalStateException`->409, and `MethodArgumentNotValidException`->400 (first field error only). (`common\exception\GlobalExceptionHandler`)
- Unknown IDs vary: services throwing `IllegalArgumentException` map to 400; event/type lookup `ResponseStatusException` uses Spring's framework handling (status indicated by code, exact JSON body UNKNOWN); ticket query/cancellation use `ResponseStatusException` 403. (`OrderService`; `EventService`; `TicketTypeService`; `TicketQueryService`; `TicketCancellationService`)
- Wrong login credentials throw Spring Security `BadCredentialsException`; no matching handler exists in `GlobalExceptionHandler`, so an explicit clear JSON message/shape is **UNKNOWN**. Duplicate email is 400, not 409. (`UserService.login/register`; `GlobalExceptionHandler`)
- Validation is 400 with first field message; JSON parse, gateway runtime, and unhandled server errors do not have a dedicated standardized handler. Exact bodies/status for unhandled framework/security failures are **UNKNOWN**. (`GlobalExceptionHandler`; `PaymentController`; `PayChanguService`)

# 11. TESTS

| Test class | Covers | Gaps / execution |
|---|---|---|
| `TicketmasterApplicationTests` | Spring context loads | Fails in current wrapper run: `NoClassDefFoundError/ClassNotFoundException: PayChanguService` in test classpath. |
| `UserServiceTest` | register success/duplicate/hash; login valid/bad credentials/user absent | All 6 errored in this Java 26 runtime because Byte Buddy cannot instrument Java 26 classes; no controller/error-shape integration coverage. |
| `OrderServiceTest` | missing user, status idempotency, cancellation owner | 2 of 3 errored due Byte Buddy; no successful place-order inventory/price/concurrency coverage. |
| `TicketControllerTest` | ticket ownership via user DB id | Error due Byte Buddy. |
| `TicketIssuingServiceTest` | issue count/code, idempotency, unpaid, invalid quantity | 2 of 4 errored due Byte Buddy; no concurrent issue test. |
| `TicketQueryServiceTest` | owner-filtered list/detail | 3 tests pass. |
| `TicketValidationControllerTest` | successful response and blank-code validation | Both errored due Byte Buddy. |
| `TicketValidationServiceTest` | success/used/cancelled/unknown/blank | 3 of 5 errored due Byte Buddy. |

Overall current run: 25 tests, 0 assertion failures, 17 errors; green test run is absent. Untested important flows include gateway requests/callback verification, authorization matcher behavior, profile identity, event/ticket-type ownership, image upload, stock races/cancellation expiry, payment-to-ticket integration, and end-to-end orders. (`src/test/java/**`; Maven Wrapper/Surefire output)

# 12. GAP MATRIX AGAINST THE MOBILE APP

| # | App need | Status | Where in code / endpoint to use or propose | Minimal change needed | Risk | Effort |
|---:|---|---|---|---|---|---|
| 1 | Register name/email/password -> token plus user{id,name,email,role} | DIFFERENT | Existing `POST /api/auth/register`; `RegisterRequest`, `AuthResponse` | Add name/profile persistence and return user summary; do not allow arbitrary elevated role | High | M |
| 2 | Login same shape; bad credentials 401 clear; duplicate 409 | DIFFERENT | `POST /api/auth/login`, `/register`; `GlobalExceptionHandler` | Return user; explicitly map credentials to 401 and duplicate to 409 | Med | S |
| 3 | Customer profile GET/PUT | PARTIAL | `GET/PUT /api/customers/me` | Resolve authenticated User id, remove id-1 fallback, enforce customer access | High | S |
| 4 | Public event list with filters/images/status/remaining/min price/ticket types | MISSING | `GET /api/events`; ticket types separately at `/api/events/{eventId}/ticket-types` | Add event filters/DTO, category/image fields, published-only policy, aggregates | High | L |
| 5 | Public event by id | EXISTS | `GET /api/events/{id}` | Decide public status visibility; current endpoint returns any status | Med | S |
| 6 | One ticket type/quantity 1, server price, logged-in customer | PARTIAL | `POST /api/orders` | Enforce exactly one item/quantity 1 if app policy; verify type belongs to event and event is published | High | S |
| 7 | Pay order with PayChangu checkoutUrl and tx_ref | DIFFERENT | `POST /api/payments/checkout` | Load order amount/owner server-side and return persisted tx_ref with URL | High | M |
| 8 | Server verify tx_ref/status/currency/amount | MISSING | Proposed `POST /api/payments/verify` | Add authenticated server-to-gateway verification and idempotent state transition | Critical | L |
| 9 | Off-by-default demo payment when callback unreachable | MISSING | Proposed `POST /api/dev/orders/{id}/mark-paid` (dev profile only) | Add disabled-by-default, profile-gated test payment; never use production path | High | M |
| 10 | Full ticket details after payment incl buyer, payment ref, QR string | PARTIAL | `GET /api/tickets/my`; `TicketResponse` | Add response DTO fields and QR content; expose ticket list after confirmed payment | Med | M |
| 11 | My orders/tickets owner-only | PARTIAL | `GET /api/orders/my`, `GET /api/tickets/my` | Keep ownership filters; restrict ticket routes to CUSTOMER if required | Med | S |
| 12 | Safe stock, release on cancel/failure/expiry | PARTIAL | `OrderService`; `TicketTypeRepository.findByIdForUpdate` | Add pending-order expiry/release and atomic/idempotent state transitions | Critical | L |
| 13 | Offline-signed QR and scan exactly once | PARTIAL | `/api/tickets/validate`; `/api/auth/qr/{text}` | Sign verifiable payload and provide public key/manifest; current scan lock is online-only | High | L |
| 14 | Usable customer/organizer/validator roles | PARTIAL | `User.Role`; `SecurityConfig` | Restrict role assignment; restore event/type ownership; define role matchers | Critical | M |
| 15 | Organizer cover image upload/static serving | MISSING | Proposed `POST /api/organizer/events/{eventId}/image` | Add authorized upload, storage policy, and static URL serving | Med | M |
| 16 | CORS localhost web + public health | PARTIAL | `SecurityConfig.corsConfigurationSource`; proposed `/actuator/health` | Replace wildcard with allowed origins; add health endpoint/Actuator | Low | S |
| 17 | Seed customer/organizer/validator/sample events and ticket types | MISSING | Existing seeder only seeds interests | Add explicit dev-only idempotent seed data; no production default passwords | Med | M |
| 18 | One consistent JSON error format | PARTIAL | `GlobalExceptionHandler` | Handle security/auth/404/gateway errors and validation field lists consistently | Med | M |
| 19 | Secrets from env and `.env.example` | DIFFERENT | `application.yml`, `docker-compose.yml`; no `.env.example` | Remove hardcoded DB/JWT secrets; require env/secrets and document placeholders | Critical | S |
| 20 | Green test run | MISSING | Maven Wrapper tests | Run under supported Java or update test runtime compatibility, resolve classpath error, add integration coverage | High | M |

# 13. EXAMPLE PAYLOADS

Examples use the real request/response field names. Example values are illustrative; no real credentials/tokens are included. `LocalDateTime` string format is UNKNOWN because source has no explicit serializer/format configuration.

| Use | Example | Classification |
|---|---|---|
| Register request | `{"email":"ada@example.test","password":"example-pass","role":"CUSTOMER"}` | REAL - `RegisterRequest` |
| Register/login response | `{"token":"<JWT>","refreshToken":"<JWT>"}` | REAL - `AuthResponse`; no `user` object |
| Login request | `{"email":"ada@example.test","password":"example-pass"}` | REAL - `LoginRequest` |
| List events response | `[{"id":7,"organizerId":2,"title":"Sample event","venue":"Bingu Stadium","location":"Lilongwe","dateTime":"UNKNOWN","startTime":"UNKNOWN","endTime":"UNKNOWN","status":"PUBLISHED","organizerUsername":"organizer@example.test"}]` | REAL field shape - `Event`; date serialization UNKNOWN; no category/image/ticketTypes |
| Event by id | Same `Event` shape as list item | REAL - `EventController.getEventById` |
| Place order request | `{"eventId":7,"items":[{"ticketTypeId":11,"quantity":1}]}` | REAL - `PlaceOrderRequest` |
| Place order/my-order response | `{"id":41,"eventId":7,"totalAmount":12000.00,"status":"PENDING","createdAt":"UNKNOWN","items":[{"ticketTypeId":11,"quantity":1,"unitPrice":12000.00}]}` | REAL field shape - `OrderResponse`/`OrderItemResponse`; date serialization UNKNOWN |
| Pay-for-order request/response | Request `{"amount":12000.00,"orderId":41}`; response `{"checkoutUrl":"<gateway URL>"}` | REAL - current `PaymentController.checkout`; tx_ref is not returned |
| Payment callback | No callback body. Query: `tx_ref=<generated UUID>`; response is a redirect to app URI | REAL - `PaymentController.callback`; gateway verification UNKNOWN/absent |
| Ticket response | `{"id":91,"orderId":41,"ticketCode":"<UUID>","eventId":7,"eventTitle":"Sample event","venue":"Bingu Stadium","location":"Lilongwe","eventDateTime":"UNKNOWN","eventStartTime":"UNKNOWN","eventEndTime":"UNKNOWN","ticketTypeId":11,"ticketTypeName":"Standard","ticketPrice":12000.00,"status":"VALID","issuedAt":"UNKNOWN","usedAt":null}` | REAL field shape - `TicketResponse`; timestamp formatting UNKNOWN |
| QR validation request/response | Request `{"ticketCode":"<UUID>"}`; response `{"ticketId":91,"ticketCode":"<UUID>","eventTitle":"Sample event","status":"USED","usedAt":"UNKNOWN","message":"Ticket validated successfully."}` | REAL - `TicketValidationRequest/Response`; timestamp format UNKNOWN |

# 14. RISKS AND BUGS RELEVANT TO THE APP

1. **Public order-status mutation:** anyone can call `/api/orders/{id}/status` to mark orders PAID (issuing tickets) or FAILED/CANCELLED (releasing stock). (`SecurityConfig.securityFilterChain`; `OrderController.updateStatus`; `OrderService.updateOrderStatus`)
2. **Payment initiation trusts caller input:** public checkout uses caller-provided amount/orderId, no owner/order/status validation, and omits tx_ref from response; callbacks merely redirect success. (`PaymentController.checkout/callback`; `PayChanguService.initiateCheckout`)
3. **Organizer authorization is bypassed:** event ownership check is commented out; event routes are public matchers and ticket-type creation/update have commented ownership checks. (`EventService.checkOrganizer`; `TicketTypeService.create/update`; `SecurityConfig`)
4. **Profile identity can cross users:** principal is email but profile service expects numeric id and falls back to id 1; public profile PUT can write that record. (`CustomerProfileService.currentUserId`; `SecurityConfig`)
5. **Registration permits role escalation:** caller chooses enum role, including ORGANIZER and VALIDATOR. (`RegisterRequest`; `UserService.register`)
6. **Hard-coded secret material:** JWT secret and DB password are hard-coded; Compose also provides DB credentials as values. Values intentionally omitted. (`application.yml`; `docker-compose.yml`)
7. **Paid ticket issuance is not concurrency-idempotent:** order existence check for tickets is not an atomic order-level guard; multiple status calls can race. (`TicketIssuingService.issueTickets`; `TicketRepository`)
8. **Pending stock never expires:** only explicit failed/cancelled transitions release reservations; no timeout/scheduler. (`OrderService.updateOrderStatus`; source-wide scheduler search)
9. **Orders may mix unrelated data:** `eventId` is not checked against item ticket types, and event published state is not checked. (`OrderService.placeOrder`)
10. **Public event discovery includes drafts/cancelled events and lacks mobile filters/images.** (`EventService.getAllEvents`; `Event`)
11. **Payment and order state paths are disconnected:** dormant mock PaymentService has no mapped endpoint, while PayChangu callback never updates order/payment or verifies gateway. (`PaymentService.createPayment`; `PaymentController`)
12. **Cross-origin policy is wildcard and security logging is DEBUG.** (`SecurityConfig.corsConfigurationSource`; `application.yml`)
13. **Error contracts differ:** custom errors have one shape, but security/framework/gateway failures are not consistently mapped; wrong-login response is unclear. (`GlobalExceptionHandler`; `UserService.login`; `PayChanguService`)
14. **Tests are not green:** wrapper run produced 17 errors, including Java 26/Byte Buddy incompatibility and missing PayChanguService class in context test. (`target\surefire-reports`; Maven Wrapper output)
15. **No persistent upload strategy, demo seed users/events, or offline-verifiable ticket QR is implemented.** (`src/main/java/**`; `InterestDataSeeder`; `QrCodeService`)
