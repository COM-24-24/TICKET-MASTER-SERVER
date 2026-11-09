# 0. ENVIRONMENT

| Item | Finding | Evidence |
|---|---|---|
| Java / Boot / build | Java 17 target; Spring Boot 3.3.5; Maven Wrapper | `pom.xml` |
| DB/schema | MariaDB + MariaDB driver; Hibernate `ddl-auto:update`; no migration tool | `pom.xml`; `src/main/resources/application.yml` |
| Server/profiles | `0.0.0.0:8080`; no context path or named profiles found | `application.yml`; resources |
| Swagger | `/v3/api-docs/**`; `/swagger-ui.html`, `/swagger-ui/**` | `config\SecurityConfig.securityFilterChain`; `application.yml` |
| OpenAPI capture | Existing listener returned valid HTTP 200 JSON; checked for configured secret literals and saved as `docs/api-docs.json` | Live request |
| Start | `.\mvnw.cmd spring-boot:run`; `docker compose up` runs MariaDB plus prebuilt backend image; no Dockerfile | `README.md`; `docker-compose.yml` |
| Tests | `.\mvnw.cmd test` FAIL: 25 tests, 17 errors. First underlying exception: Byte Buddy does not support Java 26/class version 70 (supports through 23/67); context test also reports `ClassNotFoundException: PayChanguService`. | Wrapper output; `target\surefire-reports` |

# 1. ENDPOINT INVENTORY

`permitAll` describes SecurityConfig only; manual controller checks are noted. Error JSON is only explicit where stated.

| # | HTTP method | FULL path | Controller.method | Access in practice | Required role | Request | Response | Error responses |
|---:|---|---|---|---|---|---|---|---|
| 1 | POST | `/api/auth/register` | `AuthController.register` | Public | None | `RegisterRequest(email @Email @NotBlank,password @NotBlank @Size(6),role @NotNull)` | `AuthResponse(token,refreshToken)` 200 | Validation/duplicate 400 |
| 2 | POST | `/api/auth/login` | `AuthController.login` | Public | None | `LoginRequest(email,password)` | `AuthResponse(token,refreshToken)` 200 | Validation 400; wrong-credential JSON/status UNKNOWN |
| 3 | POST | `/api/auth/google` | `AuthController.googleLogin` | Public | None | `GoogleLoginRequest(idToken @NotBlank)` | `AuthResponse` 200 | Invalid token 400 |
| 4 | POST | `/api/auth/refresh` | `AuthController.refreshToken` | Public | None | `RefreshTokenRequest(refreshToken)` | `AuthResponse` 200 | Explicit invalid-token exception 400; JWT parse error UNKNOWN |
| 5 | POST | `/api/events` | `EventController.createEvent` | Public matcher; manual login check | Any authenticated | Event fields; no `@Valid` | Event 201 | Anonymous 401; invalid time 400 |
| 6 | GET | `/api/events` | `EventController.getAllEvents` | Public | None | None | `List<Event>` all statuses | Framework default |
| 7 | GET | `/api/events/{id}` | `EventController.getEventById` | Public | None | `id:Long` | `Event` | Missing 404 |
| 8 | PUT | `/api/events/{id}` | `EventController.editEvent` | Public matcher; manual login check | Any authenticated | `id`; Event body | `Event` | 401/404/400 |
| 9 | PATCH | `/api/events/{id}/publish` | `EventController.publishEvent` | Public matcher; manual login check | Any authenticated | `id` | `Event` | 401/404/400 |
| 10 | PATCH | `/api/events/{id}/cancel` | `EventController.cancelEvent` | Public matcher; manual login check | Any authenticated | `id` | `Event` | 401/404/400 |
| 11 | GET | `/api/organizer/events` | `OrganizerEventController.myEvents` | Authenticated | ORGANIZER | None | Organizer's `List<Event>` | 403 |
| 12 | POST | `/api/events/{eventId}/ticket-types` | `TicketTypeController.create` | Public matcher; manual login check | Any authenticated | `eventId`; `{name,price,totalQuantity}` | TicketType 201 | 401/404/400 |
| 13 | PUT | `/api/ticket-types/{id}` | `TicketTypeController.update` | Public matcher; manual login check | Any authenticated | `id`; ticket-type request (`name` ignored) | TicketType | 401/404/400 |
| 14 | GET | `/api/events/{eventId}/ticket-types` | `TicketTypeController.list` | Public | None | `eventId`; optional `name` | `List<TicketType>` | Empty list possible |
| 15 | GET | `/api/ticket-types/{id}` | `TicketTypeController.getOne` | Public | None | `id` | TicketType / empty 404 | 404 |
| 16 | POST | `/api/orders` | `OrderController.placeOrder` | CUSTOMER matcher | CUSTOMER | `PlaceOrderRequest(eventId,items[{ticketTypeId,quantity>=1}])` | `OrderResponse(id,eventId,totalAmount,status,createdAt,items)` | Validation/stock 400; role 403 |
| 17 | GET | `/api/orders/my` | `OrderController.getMyOrders` | CUSTOMER matcher | CUSTOMER | None | `List<OrderResponse>` | 403 |
| 18 | GET | `/api/orders/{id}` | `OrderController.getOrderById` | CUSTOMER matcher + owner check | CUSTOMER, owner | `id` | `OrderResponse` | Missing 400; non-owner 403 |
| 19 | POST | `/api/orders/{id}/cancel` | `OrderController.cancelOrder` | CUSTOMER matcher + owner/PENDING check | CUSTOMER, owner | `id` | `OrderResponse` | Missing 400; non-owner 403; non-pending 409 |
| 20 | POST | `/api/orders/{id}/status` | `OrderController.updateStatus` | Explicit public matcher | None | `id`; `{status:String}` | Empty 200 | Invalid status 400; public state mutation |
| 21 | POST | `/api/payments/checkout` | `PaymentController.checkout` | Public | None | Map reads caller `amount` and `orderId` | `{checkoutUrl}`; no tx_ref | Number parsing 400; gateway runtime body UNKNOWN |
| 22 | GET | `/api/payments/callback` | `PaymentController.callback` | Public | None | required `tx_ref` query | 302 app redirect | Missing query 400 |
| 23 | GET | `/api/payments/return` | `PaymentController.returnUrl` | Public | None | optional `tx_ref`, `status` default failed | 302 app redirect | Framework default |
| 24 | GET | `/api/customers/me` | `CustomerProfileController.getMyProfile` | Public matcher; fallback user id 1 | None | None | `CustomerProfileResponse(id,userId,fullName,phone,city,latitude,longitude)` | Missing profile 409 |
| 25 | PUT | `/api/customers/me` | `CustomerProfileController.updateMyProfile` | Public matcher; fallback user id 1 | None | `CustomerProfileRequest(fullName,phone,city,latitude,longitude)` | `CustomerProfileResponse` | Validation 400 |
| 26 | GET | `/api/customers/me/interests` | `CustomerInterestController.getMyInterests` | Public matcher; manual role check | CUSTOMER | Principal | `List<CustomerInterest>` | 401/403 |
| 27 | POST | `/api/customers/me/interests` | `CustomerInterestController.addInterest` | Public matcher; manual role check | CUSTOMER | Query `interestId:Long` | CustomerInterest | Missing/duplicate 400; 401/403 |
| 28 | DELETE | `/api/customers/me/interests/{interestId}` | `CustomerInterestController.removeInterest` | Public matcher; manual role check | CUSTOMER | `interestId` | Empty | Missing 400; 401/403 |
| 29 | GET | `/api/tickets/my` | `TicketController.getMyTickets` | Authenticated; own tickets | Any authenticated | Principal | `List<TicketResponse>` | 401; missing user 409 |
| 30 | GET | `/api/tickets/{id}` | `TicketController.getTicketById` | Authenticated; owner-filtered | Any authenticated, owner | `id` | TicketResponse | Non-owner 403 |
| 31 | POST | `/api/tickets/{id}/cancel` | `TicketController.cancelMyTicket` | Authenticated; owner-filtered | Any authenticated, owner | `id` | 204 | Non-owner 403; invalid state 409 |
| 32 | POST | `/api/tickets/validate` | `TicketValidationController.validateTicket` | VALIDATOR matcher | VALIDATOR | `{ticketCode @NotBlank}` | `TicketValidationResponse(ticketId,ticketCode,eventTitle,status,usedAt,message)` | Invalid/unknown 400; used/cancelled 409 |
| 33 | GET | `/api/auth/qr/{text}` | `QrCodeController.generate` | Public | None | `text` path | PNG bytes | Blank 400; encoding error 409 |
| 34 | GET | `/api/interests` | `InterestController.getAllInterests` | Public | None | None | `List<Interest(id,name)>` | Framework default |
| 35 | GET | `/api/reports/events/{eventId}/sales` | `SalesReportController.getSalesReport` | Public | None | `eventId` | `List<SalesReportItem(ticketTypeId,ticketsSold,revenue)>` | Invalid id 400 |

README-only routes (`/api/events/search|nearby|recommended`, `POST /api/payments`, `GET /api/payments/{id}`, attendance report) are not mapped. Attendance controller/service are `.bak`. PaymentService simulation has no endpoint. (`README.md`; `report\AttendanceReportController.java.bak`; `PaymentService`)

# 2. AUTHENTICATION AND USERS

- `User/users`: `id:Long,email:String unique,password:String,role:Role,createdAt:LocalDateTime`; no name. Roles are one enum value: CUSTOMER, ORGANIZER, VALIDATOR; authorities are prefixed `ROLE_`. (`user\entity\User`; `CustomUserDetailsService.loadUserByUsername`)
- BCrypt encoder. Register requires email/password/role and caller chooses role; no name. Register/login return `{token,refreshToken}`, not user. Duplicate email -> 400, not 409. (`SecurityConfig.passwordEncoder`; `RegisterRequest`; `AuthResponse`; `UserService`)
- JWT access claims: subject email, role `ROLE_*`, iat, exp 24h; refresh subject email, exp 7d. Header `Authorization: Bearer <JWT>`. (`JwtService`; `JwtAuthenticationFilter`)
- Current-user lookup uses principal email except profile service parses numeric id then falls back to id 1. No listener creates profiles on registration. (`CustomerProfileService.currentUserId`; `UserService`; `UserRegisteredEvent`)
- Profile GET/PUT `/api/customers/me`; fields `fullName,phone,city,latitude,longitude`; response adds id/userId. Google-created accounts are CUSTOMER. Only seed data is interests, not users. (`CustomerProfileController`; `CustomerProfileRequest/Response`; `UserService.googleLogin`; `InterestDataSeeder`)

# 3. ENTITIES AND ENUMS

| Entity/table | Fields / constraints / relations | Status values and transitions |
|---|---|---|
| User/users | id,email,password,role,createdAt; unique email | Role CUSTOMER/ORGANIZER/VALIDATOR |
| CustomerProfile/customer_profiles | id,userId,fullName,phone,city,latitude,longitude; unique userId | None |
| Event/events | id,organizerId,title,venue,location,dateTime,startTime,endTime,status,organizerUsername; organizer fields scalar | DRAFT->PUBLISHED; any non-cancelled->CANCELLED |
| TicketType/ticket_types | id,event,name,price,totalQuantity,quantitySold; required Event relation; computed remaining | quantitySold increments on place; released on FAILED/CANCELLED |
| Order/orders | id,user,eventId,totalAmount,status,items,createdAt; User relation; cascade items | PENDING/PAID/FAILED/CANCELLED; no expiry |
| OrderItem/order_items | id,order,ticketTypeId,quantity,unitPrice; Order relation | None |
| Payment/payments | id,orderId,amount,status,createdAt,orderStatusUpdated,orderStatusUpdateError; index order/date | PAID/FAILED; only dormant service creates |
| Ticket/tickets | id,orderId,event,ticketType,ownerId,ticketCode,status,issuedAt,usedAt; unique ticketCode; Event/TicketType relations | VALID->USED or CANCELLED |
| Interest/interests | id,name; unique name | None |
| CustomerInterest/customer_interests | id,customerId,interest; Interest relation; unique customer+interest | None |

Evidence: respective entities in `user\entity`, `customer`, `events\entity`, `order\entity`, `entity`, `ticket`, and `interest`. Ticket status changes are in `TicketIssuingService`, `TicketValidationService`, `TicketCancellationService`; order/event transitions are in `OrderService`/`EventService`.

# 5. ORDER AND INVENTORY FLOW

- `POST /api/orders`: validation requires eventId and >=1 item; quantity >=1. `OrderService.placeOrder @Transactional` resolves principal email, locks TicketType rows with PESSIMISTIC_WRITE, checks availability, reserves quantity, computes price from DB and saves PENDING order. (`OrderController`; `PlaceOrderRequest`; `OrderService`; `TicketTypeRepository`)
- Supports multiple types/quantities; no max. Does not verify event exists/published or each type belongs to event. PAID issues tickets; FAILED/CANCELLED releases inventory. Customer cancellation checks owner and PENDING. No expiry job. (`OrderService`; `TicketIssuingService`)
- GET order detail/cancel compare owner; `/my` filters user. Public `POST /api/orders/{id}/status` can mark paid or release stock. No gateway verification. Ticket idempotency is a non-atomic existence check; concurrent PAID calls can race. (`OrderService`; `SecurityConfig`; `TicketIssuingService`)

# 6. PAYMENT FLOW

- Public `POST /api/payments/checkout` uses caller amount/orderId; no owner/order/amount check. PayChangu outgoing POST `${paychangu.base-url}/payment`, Bearer authorization header (secret value omitted), JSON amount string, currency MWK, email, fixed first/last name, UUID tx_ref, callback_url, return_url, customization, meta.order_id. (`PaymentController.checkout`; `PayChanguService.initiateCheckout`)
- Returns only `{checkoutUrl}`; tx_ref not returned/stored. Public callback GET only redirects app with success; return GET redirects using query status. No webhook signature, payment verification, order update, or tx_ref/status/currency/amount checks. (`PaymentController`; `Payment`)
- `PaymentService.createPayment`/`CreatePaymentRequest.succeed` simulate PAID/FAILED but have no mapped endpoint. Callback/return props: `paychangu.callback-url`, `paychangu.return-url`. (`PaymentService`; `CreatePaymentRequest`; `application.yml`)

# 7. TICKETS AND QR

- Tickets issue on PAID, one per unit; UUID ticketCode; response has event/type/timing/status but no buyer, payment reference, or QR content. (`TicketIssuingService`; `TicketResponse`)
- `/api/auth/qr/{text}` uses ZXing to encode exactly supplied text and returns 300x300 PNG bytes; no signature/hash. `/api/tickets/validate` requires VALIDATOR; locks row, marks VALID->USED once and returns `TicketValidationResponse`. No offline key/manifest. (`QrCodeService`; `TicketValidationService`; `TicketRepository`; `SecurityConfig`)
- Owner-filtered endpoints: `/api/tickets/my`, `/api/tickets/{id}`, `/api/orders/my`, `/api/orders/{id}`. (`TicketController`; `OrderController`; services)

# 8. SECURITY CONFIGURATION

- Matcher order: OPTIONS `/**` permitAll; then `/error`, `/api/auth/**`, Swagger/docs, `/api/payments/**`, `/api/orders/*/status`, `/api/events/**`, `/api/ticket-types/**`, `/api/interests/**`, `/api/customers/**`, `/api/reports/**` permitAll; `/api/orders/**` CUSTOMER; `/api/organizer/**` ORGANIZER; `/api/tickets/validate` VALIDATOR; rest authenticated. Status matcher bypasses CUSTOMER. (`SecurityConfig.securityFilterChain`)
- CSRF disabled; CORS origins/methods/headers are `*`; stateless; JWT filter before username/password filter. Filter catches token errors, logs, continues anonymous. (`SecurityConfig`; `JwtAuthenticationFilter`)
- Commented bypasses: organizer check `EventService.checkOrganizer` lines 92-104; ticket type create/update checks `TicketTypeService` lines 29-35 and 54-60; profile ID-1 fallback `CustomerProfileService.currentUserId` lines 24-35. Event/ticket-type mutations require login manually, not role/ownership. (`EventService`; `TicketTypeService`; `CustomerProfileService`; `EventController`; `TicketTypeController`)

# 12. GAP MATRIX

| # | App need | Status | Where / use or proposed endpoint | Minimal change | Risk | Effort |
|---:|---|---|---|---|---|---|
| 1 | Register returns token+user with name | DIFFERENT | `POST /api/auth/register` | Persist name; return user; restrict role | High | M |
| 2 | Login shape, 401 clear; duplicate 409 | DIFFERENT | `/api/auth/login`, `/register` | Return user; explicit 401/409 | Med | S |
| 3 | Customer profile GET/PUT | PARTIAL | `/api/customers/me` | Use authenticated user id; remove fallback | High | S |
| 4 | Filtered public events, image/status/stock/prices/types | MISSING | `GET /api/events`; types separate | Add fields, filter DTO, aggregates and publication rule | High | L |
| 5 | Public event detail | EXISTS | `GET /api/events/{id}` | Decide status visibility | Med | S |
| 6 | One ticket, server price, current customer | PARTIAL | `POST /api/orders` | Enforce one item/qty and type-event/status checks | High | S |
| 7 | Pay order and return URL+tx_ref | DIFFERENT | `POST /api/payments/checkout` | Verify owner/order and derive amount; return tx_ref | High | M |
| 8 | Server verify tx_ref/status/currency/amount | MISSING | Proposed `POST /api/payments/verify` | Gateway verification + idempotency | Critical | L |
| 9 | Off-by-default demo payment | MISSING | Proposed dev-only `POST /api/dev/orders/{id}/mark-paid` | Profile-gated, disabled in production | High | M |
| 10 | Full ticket after payment incl buyer/payment/QR | PARTIAL | `GET /api/tickets/my` | Extend ticket DTO and QR content | Med | M |
| 11 | Owner-only my orders/tickets | PARTIAL | `/api/orders/my`, `/api/tickets/my` | Keep ownership filters; optionally enforce CUSTOMER on tickets | Med | S |
| 12 | Stock release on cancel/failure/expiry | PARTIAL | `OrderService` | Add expiry + atomic transitions; protect public status route | Critical | L |
| 13 | Signed offline QR and one-time scan | PARTIAL | `/api/tickets/validate` | Sign payload; publish public key/manifest | High | L |
| 14 | Usable customer/organizer/validator roles | PARTIAL | `User.Role`, SecurityConfig | Restrict role assignment; restore ownership and role rules | Critical | M |
| 15 | Organizer cover image upload/static serving | MISSING | Proposed `POST /api/organizer/events/{eventId}/image` | Authorized upload/storage/static URL | Med | M |
| 16 | Localhost CORS + health | PARTIAL | Security CORS; proposed `/actuator/health` | Restrict origins; add public health endpoint | Low | S |
| 17 | Seed three roles and sample events/types | MISSING | Only interests are seeded | Add dev-only idempotent data; avoid production passwords | Med | M |
| 18 | Consistent JSON errors | PARTIAL | `GlobalExceptionHandler` | Normalize auth/404/gateway errors | Med | M |
| 19 | Env secrets + `.env.example` | DIFFERENT | `application.yml`; no example file | Remove hardcoded secrets; document env names | Critical | S |
| 20 | Green tests | MISSING | Maven Wrapper tests | Resolve Java/Byte Buddy and classpath issues; add flow tests | High | M |
