# Run locally

## Docker Compose

1. Copy `.env.example` to `.env` and set local values for all required secrets/database variables. Never commit `.env`.
2. Start the database and backend:

   ```sh
   docker compose up --build
   ```

3. Check that the backend is healthy:

   ```sh
   curl -fsS http://localhost:8080/api/health
   ```

   Expected response:

   ```json
   {"status":"ok"}
   ```

The first dev-profile startup seeds the development accounts and sample events. Uploaded covers are stored in the named `ticketmaster_uploads` volume.

To audit ticket-type event assignments in MariaDB:

```sql
SELECT tt.id AS ticket_type_id,
       tt.name AS ticket_type_name,
       tt.event_id,
       e.title AS event_title,
       tt.price,
       tt.total_quantity,
       tt.quantity_sold
FROM ticket_types tt
JOIN events e ON e.id = tt.event_id
ORDER BY tt.event_id, tt.price, tt.id;
```

## Demo payments

The Docker Compose `dev` profile uses PayChangu's hosted checkout by default. For student testing, put a **PayChangu test API key** in `.env`; the key determines whether PayChangu creates a sandbox or live checkout. Test credentials and test payment methods simulate transactions without changing live data. Never put a live API key in a student/testing environment. The backend now rejects a checkout URL unless it uses HTTPS and a PayChangu domain.

For local development, PayChangu's callback and return URLs must be reachable from the provider and the student's device. Use a public HTTPS tunnel and set `PAYCHANGU_CALLBACK_URL` and `PAYCHANGU_RETURN_URL` to its `/api/payments/callback` and `/api/payments/return` routes.

The local fake checkout remains available by setting `APP_DEMO_PAYMENT_ENABLED=true`. It does not contact PayChangu or charge money; it returns a local HTTP simulator page where the student selects **Simulate successful payment**. For a phone or emulator, set `APP_DEMO_PAYMENT_BASE_URL` to the backend address reachable from that device. Use this option only with a debug app build that allows local HTTP, or expose the simulator through HTTPS. The app should then call `/api/payments/verify` with the order ID to receive ticket details for the QR screen.

```sh
docker compose up --build --force-recreate backend
```

To use PayChangu test mode again, set `APP_DEMO_PAYMENT_ENABLED=false` and configure a PayChangu test API key. The fake hosted checkout and return routes are not registered outside the `dev` profile, regardless of this setting.

The application requires PayChangu credentials for hosted checkouts. Configure the test API key and public callback/return URLs as `PAYCHANGU_SECRET_KEY`, `PAYCHANGU_CALLBACK_URL`, and `PAYCHANGU_RETURN_URL`. `PAYCHANGU_BASE_URL` defaults to `https://api.paychangu.com`; a test API key selects PayChangu's test mode. If webhooks are enabled in the PayChangu dashboard, set `PAYCHANGU_WEBHOOK_SECRET` to the dashboard's webhook secret and configure `POST /api/payments/webhook`; webhooks are rejected while the secret is unset.

## Run tests locally

Use JDK 17 for the project test suite. Newer JDKs may encounter Byte Buddy compatibility failures.

```sh
./mvnw clean test
```

On Windows PowerShell, use:

```powershell
.\mvnw.cmd clean test
```
