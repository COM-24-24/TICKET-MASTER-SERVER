# Run locally

## Docker Compose

1. Copy `.env.example` to `.env` and set local values for all required secrets/database variables. Never commit `.env`.
2. Start the database and backend:

   ```sh
   docker compose up --build
   ```

3. Check that the backend is healthy:

   ```sh
   curl -fsS http://127.0.0.1:8082/api/health
   ```

   Expected response:

   ```json
   {"status":"ok"}
   ```

The API listens on `0.0.0.0:8082` both inside and outside the container. Docker Compose
publishes it on `127.0.0.1:8082`.
When running the app directly from the repository, Spring Boot also loads the optional
root `.env` file; restart the app after changing its variables.

After changing the Compose port mapping, recreate the backend container:

```sh
docker compose up --build -d --force-recreate backend
```

For a physical Android phone connected by USB, install ADB, enable USB debugging, and
run this on the computer:

```sh
adb reverse tcp:8082 tcp:8082
```

Configure the Flutter app's API base URL as `http://localhost:8082`. The app's
requests then reach the host's IPv4 loopback port 8082 through ADB reverse.

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

PayChangu must be able to reach the callback and return endpoints over a publicly reachable HTTPS address. `localhost` and the USB-forwarded host port are not reachable by PayChangu. Set both values in `.env` to the public HTTPS origin (for example, a tunnel URL), keeping the paths separate:

```dotenv
PAYCHANGU_CALLBACK_URL=https://<public-host>/api/payments/callback
PAYCHANGU_RETURN_URL=https://<public-host>/api/payments/return
```

PayChangu redirects successful payments to `callback_url` and cancellations/failed attempts to `return_url`. Both backend routes verify the transaction before redirecting to the configured app link. The redirect includes `orderId`, `status`, and `tx_ref`; the app must call authenticated `POST /api/payments/verify` for the saved order and must not treat the redirect status as proof of payment. Keep `APP_PAYMENT_REDIRECT_URI=myapp://payment-result` aligned with the Android app's registered link. The PayChangu webhook remains the separate `POST /api/payments/webhook` endpoint and must also be publicly reachable when enabled.

After changing `.env`, recreate the backend:

```sh
docker compose up --build -d --force-recreate backend
```

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
