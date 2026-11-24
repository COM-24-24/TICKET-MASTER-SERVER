# Customer Interest API Documentation

> **Implementation status:** This document describes the code currently present in this repository. The backend has a customer-selected **interest preference list**, not an event-view/search/favorites tracking system or recommendation engine. “Home Feed” guidance below distinguishes available event-list behavior from functionality that would need backend work.

## 1. Service Overview

The customer-interest feature allows the authenticated customer to retrieve, add, and remove named interests. It is implemented by `CustomerInterestController`, `CustomerInterestService`, `CustomerInterestRepository`, and the `CustomerInterest` / `Interest` JPA entities.

The configured backend listens on port `8082` by default (`server.port` in `application.yml`). Use the environment-specific host in the app; for example, an emulator/device may require a reachable LAN host rather than `localhost`.

Implemented capability summary:

| Capability                                     | Implemented? | Details                                                                    |
| ---------------------------------------------- | -----------: | -------------------------------------------------------------------------- |
| List available interests                       |          Yes | Public `GET /api/interests`                                                |
| Retrieve current customer's selected interests |          Yes | Authenticated `GET /api/customers/me/interests`                            |
| Add a selected interest                        |          Yes | Authenticated `POST` with `interestId` query parameter                     |
| Remove a selected interest                     |          Yes | Authenticated `DELETE` with interest ID path parameter                     |
| Update an interest / reset all interests       |           No | No such route/service operation exists                                     |
| Track event searches/views                     |           No | Search is an event-list filter only; no event-interaction write is present |
| Track ticket purchases as interests            |           No | Orders/tickets are separate; no code writes customer interests from them   |
| Explicit event favorites                       |           No | No event-favorite entity or endpoint found                                 |
| Personalized Home Feed / recommendations       |           No | No recommendation endpoint or interest-based ranking found                 |

“Interest” means a catalog item such as `Music` or `Sports`, not an event. These records are explicit customer preferences, not automatically inferred behavior.

## 2. Business Logic

1. The client can list catalog interests without authentication.
2. For customer-specific operations, the JWT-authenticated identity is resolved by email from the `users` table. The controller checks that the account role is `CUSTOMER`; clients do not submit a customer ID.
3. Adding an interest checks whether that customer/interest pair already exists. A duplicate throws `IllegalArgumentException` (“You have already selected this interest.”). Otherwise the service looks up the interest by ID and saves a new customer-interest association.
4. Removing an interest deletes the matching pair; if it does not exist, the service throws `IllegalArgumentException` (“Selected interest not found.”).
5. There are no scores, counts, interaction timestamps, recency weights, event association, or category matching in customer-interest service logic.
6. Available interests may be seeded when `app.seed.enabled=true`. The seeder inserts missing default names (case-insensitive check): `Music`, `Sports`, `Tech`, `Community`, `Food`, `Art`, `Fitness`. The default setting in `application.yml` is disabled.

Search on `GET /api/events` accepts optional `category` and `search`; it filters published event records but does not persist a query or update interests. Event details `GET /api/events/{id}` returns event information without logging a view. Order/ticket operations create/read order/ticket data independently; no interest update call occurs in those flows.

## 3. Database Structure

### Entities and relationships

- `users`: customer accounts and role. The `customer_interests.customer_id` value corresponds to a `users.id`. The customer-interest JPA model stores this as a scalar `Long`, rather than a JPA `User` relationship.
- `interests`: catalog with numeric ID and unique name.
- `customer_interests`: association row that has `customer_id` and a many-to-one `interest_id` in the JPA entity. JPA enforces uniqueness on `(customer_id, interest_id)`.
- `events`: event category is a free-form string (`Event.category`), not a foreign key to the `interests` catalog. Therefore there is no enforced mapping from a customer interest to an event category.
- `orders` and `tickets`: separate purchase records. The order references its user and event ID; ticket records reference event, owner and order. No customer-interest relation is defined.

### Important schema/entity mismatch

The checked-in `schema.sql` defines `customer_interests` with a **composite primary key** `(customer_id, interest_id)` and no `id` column. The JPA `CustomerInterest` entity instead maps a generated `id` as `@Id` and separately declares a unique constraint on `(customer_id, interest_id)`. Likewise `schema.sql` declares `interests.name VARCHAR(80)`, while the entity declares length 100. `application.yml` uses `spring.jpa.hibernate.ddl-auto: update`, which may alter a live schema, but actual deployment migration/database state is not established by source inspection. Align the DDL and entity mapping before relying on the serialized generated `id` or schema bootstrap behavior.

`schema.sql` also describes some event/order fields that differ from entity mappings. This further makes the deployed schema/migration history important; the JSON examples below represent the expected JPA serialization shape, not a guarantee about every existing database.

## 4. API Endpoint Reference

Base URL is deployment-specific, for example `http://<host>:8082`. All paths below are relative to it.

### 4.1 List available interest catalog

- **Method/path:** `GET /api/interests`
- **Purpose:** Return all `Interest` entities.
- **Authentication:** Not required; `SecurityConfig` permits `GET /api/interests/**`.
- **Headers:** `Accept: application/json` recommended. No request body or query/path parameters.
- **Validation:** No request parameters/body to validate.
- **Database operations:** `InterestRepository.findAll()`; catalog seeding is separate startup behavior.
- **Success:** `200 OK`, JSON array of interest objects; empty catalog returns `[]`.
- **Possible errors:** `500 Internal Server Error` for an unexpected server/database error. A bad path may return 404. Authentication is not required.

### 4.2 Get current customer's interests

- **Method/path:** `GET /api/customers/me/interests`
- **Purpose:** Return the authenticated customer's selected interest association rows.
- **Authentication:** Required. Authenticated user must have role `CUSTOMER`; unauthenticated/invalid identity gives 401, another role gives 403.
- **Headers:** `Authorization: Bearer <access-token>`, `Accept: application/json`.
- **Parameters/body:** None.
- **Validation:** Authentication and customer-role checks. No interest list filters or pagination.
- **Database operations:** `UserRepository.findByEmail(authentication.name)` followed by `CustomerInterestRepository.findByCustomerId(user.id)`; interested entities are returned through JPA/Jackson serialization.
- **Success:** `200 OK`, JSON array; no selections gives `[]`.
- **Possible errors:** `401 Unauthorized` (including missing/invalid identity), `403 Forbidden` (non-customer role), `500 Internal Server Error` (unexpected DB/serialization failure).

### 4.3 Add a selected interest

- **Method/path:** `POST /api/customers/me/interests?interestId={id}`
- **Purpose:** Add an existing catalog interest to the authenticated customer's preference set.
- **Authentication:** Required; customer role only.
- **Headers:** `Authorization: Bearer <access-token>`, `Accept: application/json`. `Content-Type` is not required because there is no request body.
- **Request parameter:** Required query parameter `interestId`, a numeric `Long` catalog ID. Do not send a JSON body; controller parameter is `@RequestParam Long interestId`.
- **Validation:** Must bind to a number and identify an existing `interests.id`. A customer/interest pair must not already exist. No bean-validation constraints are declared. Missing/malformed query parameter handling may differ because the global handler does not specifically handle Spring's missing-parameter exception; send a valid numeric parameter.
- **Database operations:** Resolve current user and role; check pair existence; find interest by ID; insert `customer_interests` association.
- **Success:** `200 OK` with serialized `CustomerInterest` (the method does not set 201). See the representative shape in Section 5.
- **Possible errors:** `400 Bad Request` for duplicate association or unknown interest (`IllegalArgumentException` is mapped to 400); `401 Unauthorized`; `403 Forbidden`; `500 Internal Server Error` for unexpected errors. A malformed/missing parameter should be treated as a client error by the client, but due to broad exception handling its precise server status should be confirmed against the deployed version.

### 4.4 Remove a selected interest

- **Method/path:** `DELETE /api/customers/me/interests/{interestId}`
- **Purpose:** Remove the current customer's selection of the given catalog interest.
- **Authentication:** Required; customer role only.
- **Headers:** `Authorization: Bearer <access-token>`, `Accept: application/json` recommended. No request body.
- **Path parameter:** `interestId`, numeric catalog ID (not `customer_interests.id`).
- **Validation:** Must be a numeric ID and the pair must already exist for this customer. There is no idempotent/no-op behavior for missing selections.
- **Database operations:** Resolve user and role; find matching pair; delete the association row.
- **Success:** The controller returns `void` and does not declare `204`; Spring's default is `200 OK` with an empty response body.
- **Possible errors:** `400 Bad Request` if that selection is not present; `401 Unauthorized`; `403 Forbidden`; `500 Internal Server Error` for unexpected errors. A nonexistent URL itself may return 404.

### 4.5 Event list endpoint (available Home Feed fallback, not a recommendation API)

- **Method/path:** `GET /api/events`
- **Purpose:** List published events, optionally filtered by exact category (case/space normalized) and substring search over title, venue, or location.
- **Authentication:** Public GET is permitted. An optional bearer token may be sent, but the result does not become personalized.
- **Headers:** `Accept: application/json` recommended.
- **Parameters:** Optional `category` and `search` strings. No body.
- **Database/business operation:** Reads published events ordered by `startTime` ascending, then applies category/search filters and builds `EventResponse` including ticket availability and pricing.
- **Success:** `200 OK`, array of `EventResponse`; no matches returns `[]`.
- **Possible errors:** `500 Internal Server Error` for an unexpected error; invalid URL/path may be 404. No recommender-specific errors exist.

## 5. Request/Response Examples

IDs and names below are illustrative values, not fixed server records.

### Catalog

```http
GET /api/interests
Accept: application/json
```

```json
[
  { "id": 1, "name": "Music" },
  { "id": 2, "name": "Sports" }
]
```

### Retrieve selected interests

```http
GET /api/customers/me/interests
Authorization: Bearer eyJ...
Accept: application/json
```

```json
[
  {
    "id": 14,
    "customerId": 57,
    "interest": { "id": 1, "name": "Music" }
  }
]
```

For no interests, response is `[]`. The `id` member is affected by the `CustomerInterest` JPA/schema mismatch described above; the association and nested `interest` shape are what the entity getters expose.

### Add an interest

```http
POST /api/customers/me/interests?interestId=2
Authorization: Bearer eyJ...
Accept: application/json
```

No request body. Representative response (`200 OK`):

```json
{
  "id": 15,
  "customerId": 57,
  "interest": { "id": 2, "name": "Sports" }
}
```

### Remove an interest

```http
DELETE /api/customers/me/interests/2
Authorization: Bearer eyJ...
```

Expected successful response: `200 OK`, empty body.

### Error response shape

The global handler formats many errors as:

```json
{
  "timestamp": "2026-11-23T10:20:30.123",
  "status": 400,
  "error": "Bad Request",
  "message": "Interest not found.",
  "path": "/api/customers/me/interests"
}
```

Security entry/denial handlers similarly return `timestamp`, `status`, `error`, `message`, and `path`. Actual timestamp and messages vary. Do not rely on an exact error message for control flow; use HTTP status and parse fields defensively.

### Event list/Home fallback sample

```http
GET /api/events?category=Music
Accept: application/json
```

```json
[
  {
    "id": 301,
    "title": "Live Music Night",
    "category": "Music",
    "genre": "Jazz",
    "venue": "City Hall",
    "location": "Central District",
    "startTime": "2026-12-10T19:00:00",
    "endTime": "2026-12-10T22:00:00",
    "status": "PUBLISHED",
    "availability": "AVAILABLE",
    "coverImagePath": "/uploads/events/301.jpg",
    "minPrice": 25.0,
    "capacity": 300,
    "remainingTickets": 120,
    "ticketTypes": [
      {
        "id": 8,
        "name": "General",
        "price": 25.0,
        "totalQuantity": 200,
        "remaining": 80
      }
    ]
  }
]
```

This is a sample of the existing `EventResponse` DTO and response builder; event-list results are ordered by start time ascending, not interest score or popularity. Date-time values are local date-time strings without an offset, matching `LocalDateTime` serialization.

## 6. Interest Tracking Mechanism

### What the code actually tracks

The only implemented recording action is the customer's explicit selection of a catalog interest via `POST /api/customers/me/interests?interestId=...`. It can be retrieved via GET and removed via DELETE.

| Customer action          | Automatically creates/updates a customer-interest record? | Actual behavior                                                              |
| ------------------------ | --------------------------------------------------------: | ---------------------------------------------------------------------------- |
| Search for an event      |                                                        No | Filters `GET /api/events`; no persistence of search terms or customer ID     |
| Browse an event category |                                                        No | A category filter can be requested; no interest update is performed          |
| View an event            |                                                        No | `GET /api/events/{id}` only returns details                                  |
| Purchase tickets         |                                                        No | Order/ticket data is persisted separately; no customer-interest service call |
| Mark event favorite      |                                                        No | No event favorites API/table found                                           |
| Select a named interest  |                                                       Yes | Adds one association to `customer_interests`                                 |

There is no per-interest score, event-level interest, category relation, interaction history, repetition count or timestamp. Duplicate selection is rejected rather than increasing a score. A customer can remove each selection individually. There is no bulk reset endpoint; the client would need to DELETE each existing selection, or backend functionality would need to be added.

## 7. Home Feed Recommendation Logic

**No dedicated Home Feed endpoint or recommendation logic exists in the inspected source.** The source search found no personalized/recommendation service, controller, repository query or test.

The closest current behavior is `GET /api/events`:

- Only events with status `PUBLISHED` are listed.
- Results are ordered chronologically by start time ascending before optional category/search filtering.
- Search matches title, venue or location case-insensitively by substring; it does not match category or genre.
- No customer interest is read, and the optional authentication context does not personalize the response.
- Event response contains availability, min price, capacity and remaining ticket counts. The list does **not** filter out sold-out events; response marks availability as `SOLD_OUT` when ticket types exist and remaining is zero. Cancelled and draft events are not included by the published-only list query. Ended events are not filtered out by that query, but response availability can be `ENDED`.
- There is no popularity ranking or behavior-based ranking. No-interest users receive the same event-list logic as everyone else; there is no separate recommendation empty-state behavior.

For the Flutter Home screen today, call `GET /api/events` and render its chronological published-event list. The app may choose a display fallback locally, such as chronological upcoming events and an empty-state message, but should not label the result “personalized” unless it is clearly a client-side filter and understood to be non-authoritative. To implement server personalization, backend work is needed: connect catalog interests to event categories/tags, define ranking and eligibility rules, add a recommendation endpoint, and specify pagination/fallback behavior.

## 8. Flutter Integration Guide

### A. Data models matching current API

`Interest` is the catalog object; `CustomerInterest` is the association returned for customer operations. There is no event-interest model in the backend. Event list uses `EventResponse`, not a recommendation wrapper.

```dart
class Interest {
  const Interest({required this.id, required this.name});
  final int id;
  final String name;

  factory Interest.fromJson(Map<String, dynamic> json) => Interest(
        id: (json['id'] as num).toInt(),
        name: json['name'] as String,
      );
}

class CustomerInterest {
  const CustomerInterest({
    required this.id,
    required this.customerId,
    required this.interest,
  });
  final int id;
  final int customerId;
  final Interest interest;

  factory CustomerInterest.fromJson(Map<String, dynamic> json) =>
      CustomerInterest(
        id: (json['id'] as num).toInt(),
        customerId: (json['customerId'] as num).toInt(),
        interest: Interest.fromJson(
          json['interest'] as Map<String, dynamic>,
        ),
      );
}

class EventTicketType {
  const EventTicketType({
    required this.id,
    required this.name,
    required this.price,
    required this.totalQuantity,
    required this.remaining,
  });
  final int id;
  final String name;
  final double price;
  final int? totalQuantity;
  final int? remaining;

  factory EventTicketType.fromJson(Map<String, dynamic> json) =>
      EventTicketType(
        id: (json['id'] as num).toInt(),
        name: json['name'] as String,
        price: (json['price'] as num).toDouble(),
        totalQuantity: (json['totalQuantity'] as num?)?.toInt(),
        remaining: (json['remaining'] as num?)?.toInt(),
      );
}

class HomeFeedEvent {
  const HomeFeedEvent({
    required this.id,
    required this.title,
    required this.category,
    required this.genre,
    required this.venue,
    required this.location,
    required this.startTime,
    required this.endTime,
    required this.status,
    required this.availability,
    required this.coverImagePath,
    required this.minPrice,
    required this.capacity,
    required this.remainingTickets,
    required this.ticketTypes,
  });
  final int id;
  final String title;
  final String? category;
  final String? genre;
  final String venue;
  final String location;
  final DateTime startTime;
  final DateTime endTime;
  final String status;
  final String availability;
  final String? coverImagePath;
  final double minPrice;
  final int capacity;
  final int remainingTickets;
  final List<EventTicketType> ticketTypes;

  factory HomeFeedEvent.fromJson(Map<String, dynamic> json) => HomeFeedEvent(
        id: (json['id'] as num).toInt(),
        title: json['title'] as String,
        category: json['category'] as String?,
        genre: json['genre'] as String?,
        venue: json['venue'] as String,
        location: json['location'] as String,
        startTime: DateTime.parse(json['startTime'] as String),
        endTime: DateTime.parse(json['endTime'] as String),
        status: json['status'] as String,
        availability: json['availability'] as String,
        coverImagePath: json['coverImagePath'] as String?,
        minPrice: (json['minPrice'] as num).toDouble(),
        capacity: (json['capacity'] as num).toInt(),
        remainingTickets: (json['remainingTickets'] as num).toInt(),
        ticketTypes: (json['ticketTypes'] as List<dynamic>)
            .map((item) => EventTicketType.fromJson(
                  item as Map<String, dynamic>,
                ))
            .toList(),
      );
}
```

`CustomerInterest.id` currently follows the JPA entity mapping. The schema discrepancy may mean that deployment behavior differs; consider making the parser's ID optional until entity and database schema are aligned. The shown event model follows the DTO fields; status/availability are strings to retain backend enum values without assuming future enum changes. For event date-times with no zone offset, interpret according to the backend's documented deployment timezone; the payload itself does not carry a timezone.

### B. API service layer

The example below uses `package:http/http.dart` and deliberately sends interest IDs in the query/path exactly as the controller expects. Install/use `http` in the app and inject the access token from your existing auth/session layer. Do not send a guessed JSON body to the POST.

```dart
import 'dart:convert';
import 'package:http/http.dart' as http;

class CustomerInterestApi {
  CustomerInterestApi({required this.baseUrl, required this.tokenProvider});

  final String baseUrl; // e.g. https://api.example.com (no trailing slash)
  final Future<String?> Function() tokenProvider;

  Future<Map<String, String>> _headers({bool authenticated = true}) async {
    final headers = <String, String>{'Accept': 'application/json'};
    if (authenticated) {
      final token = await tokenProvider();
      if (token == null || token.isEmpty) {
        throw StateError('An access token is required for this operation.');
      }
      headers['Authorization'] = 'Bearer $token';
    }
    return headers;
  }

  Uri _uri(String path, [Map<String, String>? query]) =>
      Uri.parse('$baseUrl$path').replace(queryParameters: query);

  void _check(http.Response response) {
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw ApiException(response.statusCode, response.body);
    }
  }

  Future<List<Interest>> getAvailableInterests() async {
    final response = await http.get(
      _uri('/api/interests'),
      headers: await _headers(authenticated: false),
    );
    _check(response);
    return (jsonDecode(response.body) as List<dynamic>)
        .map((item) => Interest.fromJson(item as Map<String, dynamic>))
        .toList();
  }

  Future<List<CustomerInterest>> getMyInterests() async {
    final response = await http.get(
      _uri('/api/customers/me/interests'),
      headers: await _headers(),
    );
    _check(response);
    return (jsonDecode(response.body) as List<dynamic>)
        .map((item) => CustomerInterest.fromJson(item as Map<String, dynamic>))
        .toList();
  }

  Future<CustomerInterest> addInterest(int interestId) async {
    final response = await http.post(
      _uri('/api/customers/me/interests', {'interestId': '$interestId'}),
      headers: await _headers(),
    );
    _check(response);
    return CustomerInterest.fromJson(
      jsonDecode(response.body) as Map<String, dynamic>,
    );
  }

  Future<void> removeInterest(int interestId) async {
    final response = await http.delete(
      _uri('/api/customers/me/interests/$interestId'),
      headers: await _headers(),
    );
    _check(response); // Current controller responds 200 with an empty body.
  }

  Future<List<HomeFeedEvent>> getEvents({String? category, String? search}) async {
    final query = <String, String>{};
    if (category != null && category.trim().isNotEmpty) {
      query['category'] = category.trim();
    }
    if (search != null && search.trim().isNotEmpty) {
      query['search'] = search.trim();
    }
    final response = await http.get(
      _uri('/api/events', query.isEmpty ? null : query),
      headers: await _headers(authenticated: false),
    );
    _check(response);
    return (jsonDecode(response.body) as List<dynamic>)
        .map((item) => HomeFeedEvent.fromJson(item as Map<String, dynamic>))
        .toList();
  }
}

class ApiException implements Exception {
  ApiException(this.statusCode, this.responseBody);
  final int statusCode;
  final String responseBody;
  @override
  String toString() => 'API request failed ($statusCode): $responseBody';
}
```

There is no supported update, bulk-remove, event-favorite, event-view/search tracking, or recommendation method to add to this service without corresponding backend endpoints.

### C. Integration architecture

```text
Flutter UI (Home / Search / Event Details / Preferences)
        ↓
Flutter state/repository layer
        ↓ HTTPS + Authorization: Bearer <JWT> for customer endpoints
Customer Interest endpoints / public Events and Interest catalog endpoints
        ↓
Spring controllers → services → Spring Data repositories
        ↓
users + customer_interests + interests / events + orders + tickets
```

`/me` uses authenticated identity; never trust a customer ID supplied by the app for these routes. Keep network calls out of widgets where possible, surface loading/error state in a repository/provider/BLoC/ViewModel, and cache only with a user-scoped key.

### D. UI integration points

- **Search screen:** Call `GET /api/events?search=...`. The search is not currently tracked. Do not call `addInterest` for each query unless the product intentionally treats an explicit preference selection as search tracking; it accepts only catalog interest IDs, not query strings.
- **Event details:** Call `GET /api/events/{id}` to show details. No view-tracking endpoint exists.
- **Preferences / explicit favorites:** The current interest picker can load `GET /api/interests`, then add/remove selections with the customer-interest API. These are named preference interests, not event favorites. If the UX needs an event-level favorite button, backend support is missing.
- **Ticket purchase confirmation / synchronization:** The backend does not convert purchases into interest records. The app may display purchase history via its existing order/ticket APIs, but must not represent purchases as customer-interest tracking on the server.
- **Home Feed:** Call `GET /api/events` and render chronological published events. It is not personalized. If adding category chips, they can pass the category filter, but the backend still will not apply selected customer interests automatically.

### E. Error handling

- **401:** Access token missing/expired/invalid or authenticated user not found. Refresh/re-authenticate using the app's established auth flow, then retry at most once as appropriate.
- **403:** User is authenticated but is not a `CUSTOMER`. Do not keep retrying; disable customer-only preferences for that account.
- **Network/timeout:** Keep a stale local read-only cache where available, present retry, and queue user intent only under the offline policy in Section 9.
- **Empty interests:** `GET /api/customers/me/interests` returns `[]`; show preference onboarding or optional recommendations based on the existing general event list. Empty is not an error.
- **Invalid interest ID:** Add reports 400 (“Interest not found.”). Refresh the public catalog and discard stale IDs; do not retry unchanged.
- **Duplicate interest:** Add reports 400 (“You have already selected this interest.”), not a successful idempotent response or score increase. Reconcile by fetching current selections; avoid parallel duplicate requests from double taps.
- **Removing missing selection:** Reports 400 (“Selected interest not found.”). Re-fetch to reconcile. For a UI that is already in the removed state, the client can treat this as stale state only after confirming via GET.
- **Server/serialization failure:** 500 (and possibly differently surfaced errors for invalid/missing query parameters due to handler behavior). Keep the UI usable with cached/general events and report diagnostics without exposing raw server details to the customer.

## 9. SQLite/Offline Considerations

The backend has no offline-sync API, idempotency key, bulk upsert, interest revision/timestamp, or event-interaction upload endpoint. Therefore a durable automatic synchronization mechanism cannot safely be implemented against the current API as if replay were idempotent: replaying an add after a successful-but-unacknowledged request may result in the duplicate `400`; DELETE of an already-removed row also returns `400`.

A safe client approach with current functionality:

1. Cache the public interest catalog, last successful customer interest list (partitioned by authenticated user ID), and a recent event list in SQLite with `fetchedAt` metadata.
2. Treat the backend as source of truth when online: refresh selected interests on login/session restore and after successful add/delete; update local cache only once the operation is confirmed.
3. Offline, allow browsing cached data and show stale/offline status. Do not claim an unsubmitted interest change is synchronized.
4. If product requirements demand offline preference editing, store a local pending-intent queue (operations + user ID + created time), but mark pending state visibly. When online, reconcile with GET first and compare desired selections with server state; send only needed add/delete requests, then GET again. Handle duplicate/missing 400 by re-fetching and reconciling. Do not send queued changes across accounts.
5. For the Home Feed fallback, render cached recent events if available; otherwise fetch public `GET /api/events` when online, and show an offline empty-state/retry when there is no cache. The current general event endpoint can supply online fallback results; no personalized recommendation endpoint exists.

For robust sync and inferred behavior, backend additions would be needed: idempotent add/remove semantics (e.g. PUT/upsert or explicit success-if-existing), bulk preference read/write with conflict policy, interaction event ingestion with client event IDs/idempotency and timestamps, and optionally change/version cursors. Those are recommendations, not existing APIs.

## 10. Error Handling

### Current status mapping overview

| Situation                                               | Expected status / behavior from source                                                                                             |
| ------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| Missing/invalid customer authentication                 | 401; security entry point body includes `status`, `error`, `message`, `path`, `timestamp`                                          |
| Authenticated non-customer calling `/api/customers/**`  | 403 from controller role check                                                                                                     |
| Unknown interest on add                                 | 400 via global `IllegalArgumentException` handler                                                                                  |
| Duplicate customer-interest pair                        | 400 via global handler                                                                                                             |
| Remove non-selected interest                            | 400 via global handler                                                                                                             |
| Bad request body (not used by these interest endpoints) | 400 via global handler                                                                                                             |
| Unhandled server error                                  | 500 generic error body; server logs exception                                                                                      |
| Missing/malformed `interestId` parameter                | Client should regard as invalid request; exact status may be affected by broad exception handler and needs deployment verification |
| No selected interests / no matching event list          | 200 with `[]`                                                                                                                      |
| Successful remove                                       | 200 with empty body                                                                                                                |

Consumers should branch on status codes, tolerate unknown/missing error fields, avoid parsing error messages as stable identifiers, and not assume all server responses contain JSON (particularly the successful DELETE response).

## 11. Missing Functionality and Recommendations

The following items are not implemented and should not be treated as backend commitments:

1. **Behavioral activity tracking:** Search, event views, clicks, purchase conversion and event-favorite endpoints/records.
2. **Interest-to-event mapping:** `interests.name` and `events.category` are separate, with no foreign key, tag map or normalization policy.
3. **Recommendation service:** No home-feed route, ranking weights, popularity metrics, pagination/cursor, exclusion rules, or personalized fallback behavior.
4. **Scores and recency:** No repeated-interaction scoring or timestamps on customer-interest associations.
5. **Event favorites:** No event-specific favorite model/API; catalog interests are not a substitute.
6. **Bulk/reset/update semantics:** No replace list/reset-all operation. Current add/delete are not idempotent.
7. **Schema consistency:** Resolve `CustomerInterest.id` versus composite schema primary key and `Interest.name` 100 versus schema 80. Validate actual deployed migration/schema rather than assuming Hibernate update has reconciled it.
8. **API contract stability:** Consider response DTOs rather than returning JPA entities, stable validation errors, explicit `201` for creation and `204` for deletion, and API-level integration tests for status/body shape.
9. **Privacy/product controls:** If behavioral tracking is added, define consent, retention, deletion/export policy and whether purchases/searches may influence ranking.

## 12. Implementation Checklist

### Flutter integration with current backend

- [ ] Configure the correct environment base URL (default local backend port is `8082`).
- [ ] Obtain/store access token through existing auth and send `Authorization: Bearer <token>` for `/api/customers/me/interests` operations.
- [ ] Check the authenticated account role is customer before showing customer preference management.
- [ ] Load catalog from `GET /api/interests` and selections from `GET /api/customers/me/interests`.
- [ ] Add with query parameter `interestId`; do not send a JSON body.
- [ ] Delete with catalog `interestId` in the URL; accept `200` empty body.
- [ ] Handle duplicate/unknown/missing selections with a refresh-and-reconcile path.
- [ ] Parse actual entity/DTO response fields defensively and test against the deployed backend after resolving/understanding schema mismatch.
- [ ] Build current Home screen from public `GET /api/events`; respect `availability` and display sold-out/ended status.
- [ ] Do not claim search, view, purchase, or event favorite tracking or personalized feed exists.
- [ ] Cache interests/events per user and show stale/offline state; do not claim queued changes sync until confirmed.

### Backend follow-up before personalized recommendations

- [ ] Align JPA mappings and SQL/migration schema for interest associations and name length.
- [ ] Decide how catalog interests map to event categories/tags.
- [ ] Specify explicit event favorites separately from named interests, if required.
- [ ] Define behavior collection and consent/retention if search/view/purchase signals are required.
- [ ] Specify recommendation ranking, eligibility (cancelled/ended/sold-out), sort order, empty-interest behavior, pagination and response contract.
- [ ] Add documented APIs/services/repositories and tests before implementing Flutter calls for those features.
- [ ] Consider idempotent writes and synchronization semantics for offline clients.
