# Kalasetu Order Service

Spring Boot service that handles checkout for Kalasetu: server-side pricing, stock reservation, Razorpay payments, webhooks and the order lifecycle. The design, including failure handling and scaling, is in [`docs/DESIGN.md`](../docs/DESIGN.md).

**Stack:** Java 21, Spring Boot 4, Firebase Admin SDK (Firestore, Auth), Razorpay Orders API, Docker, Cloud Run.

## What it does

- Prices come from Firestore inside a transaction; the browser only sends product ids and quantities.
- Stock is reserved atomically when the order is created and released if payment doesn't arrive within 15 minutes. Products without a `stock` field are not limited, so existing listings keep working.
- `Idempotency-Key` on order creation: a retried request returns the same order instead of creating a second one.
- Payments are confirmed through both the Checkout callback and the Razorpay webhook. Both verify HMAC signatures, and processing the same payment twice has no effect.
- Order states: `PENDING_PAYMENT → PAID → SHIPPED → DELIVERED`, plus `EXPIRED` and `REFUND_REQUIRED`. Invalid transitions are rejected.
- An append-only ledger records what each artisan is owed per paid order.

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/orders` | Create order (header `Idempotency-Key`) |
| `GET` | `/api/v1/orders/{id}` | Get an order (buyer or seller) |
| `GET` | `/api/v1/orders?role=buyer\|seller` | List my purchases or my sales |
| `POST` | `/api/v1/orders/{id}/payment` | Confirm Razorpay Checkout payment |
| `PATCH` | `/api/v1/orders/{id}/status` | Seller sets `SHIPPED` / `DELIVERED` |
| `POST` | `/api/v1/webhooks/razorpay` | Razorpay webhook |
| `POST` | `/internal/jobs/expire-orders` | Expiry job (header `X-Job-Token`) |
| `GET` | `/actuator/health` | Health check |

`/api/v1/orders` endpoints need `Authorization: Bearer <Firebase ID token>`. Errors are `application/problem+json` with a `code` field (for example `OUT_OF_STOCK`).

## Configuration

| Variable | Required | Description |
|---|---|---|
| `RAZORPAY_KEY_ID` | yes | Razorpay API key id (`rzp_test_...` in test mode) |
| `RAZORPAY_KEY_SECRET` | yes | Razorpay API key secret |
| `RAZORPAY_WEBHOOK_SECRET` | yes | Secret set on the Razorpay webhook |
| `JOB_TOKEN` | for the expiry job | Shared secret Cloud Scheduler sends; the endpoint is disabled when empty |
| `FIREBASE_PROJECT_ID` | no | Defaults to `kalasetu-25a17` |
| `CORS_ALLOWED_ORIGINS` | no | Comma-separated; defaults to the Kalasetu Hosting domains |
| `ORDER_RESERVATION_TTL` | no | Defaults to `15m` |

Firestore and Firebase Auth use Application Default Credentials.

## Run locally (Windows PowerShell)

Needs JDK 21. Maven is not required: the project includes the Maven wrapper (`mvnw.cmd`).

Run the tests (no cloud access needed; they use an in-memory store and a fake gateway):

```powershell
cd order-service
.\mvnw.cmd test
```

Start the service against your real Firestore:

```powershell
gcloud auth application-default login
cd order-service
$env:RAZORPAY_KEY_ID = "rzp_test_xxx"
$env:RAZORPAY_KEY_SECRET = "xxx"
$env:RAZORPAY_WEBHOOK_SECRET = "xxx"
$env:JOB_TOKEN = "local-token"
.\mvnw.cmd spring-boot:run
```

Then open http://localhost:8080/actuator/health.

## Deploy

The steps below assume the project is already on the Blaze plan, which Cloud Functions requires anyway.

**0. Google Cloud setup (once).**

```powershell
gcloud auth login
gcloud config set project kalasetu-25a17
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com `
  secretmanager.googleapis.com cloudscheduler.googleapis.com

$projectNumber = gcloud projects describe kalasetu-25a17 --format "value(projectNumber)"
$serviceAccount = "serviceAccount:$projectNumber-compute@developer.gserviceaccount.com"
gcloud projects add-iam-policy-binding kalasetu-25a17 --member $serviceAccount --role roles/datastore.user
gcloud projects add-iam-policy-binding kalasetu-25a17 --member $serviceAccount --role roles/secretmanager.secretAccessor
```

**1. Razorpay (test mode).** In the Razorpay Dashboard, generate test API keys.

**2. Store secrets** (PowerShell). Each value is written to a temporary file without a trailing newline, because a newline would become part of the secret.

```powershell
function New-Secret($name, $value) {
    $file = New-TemporaryFile
    Set-Content -Path $file -Value $value -NoNewline
    gcloud secrets create $name --data-file="$file"
    Remove-Item $file
}

New-Secret razorpay-key-id "rzp_test_xxx"
New-Secret razorpay-key-secret "xxx"
New-Secret razorpay-webhook-secret "xxx"

$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$jobToken = -join ($bytes | ForEach-Object { $_.ToString("x2") })
New-Secret order-job-token $jobToken
```

**3. Deploy to Cloud Run.** Run this from the repository root. The service is public because the browser calls it directly; every order endpoint checks the Firebase token itself.

```powershell
gcloud run deploy kalasetu-order-service `
  --source order-service `
  --region asia-south1 `
  --allow-unauthenticated `
  --set-secrets "RAZORPAY_KEY_ID=razorpay-key-id:latest,RAZORPAY_KEY_SECRET=razorpay-key-secret:latest,RAZORPAY_WEBHOOK_SECRET=razorpay-webhook-secret:latest,JOB_TOKEN=order-job-token:latest"
```

**4. Firestore indexes.**

```powershell
firebase deploy --only firestore:indexes
```

If the CLI offers to delete indexes that exist in the console but not in `firestore.indexes.json`, answer no unless you know they're unused.

**5. Expiry job.** Every 5 minutes:

```powershell
$serviceUrl = gcloud run services describe kalasetu-order-service --region asia-south1 --format "value(status.url)"
$jobToken = gcloud secrets versions access latest --secret order-job-token

gcloud scheduler jobs create http expire-unpaid-orders `
  --location asia-south1 `
  --schedule "*/5 * * * *" `
  --http-method POST `
  --uri "$serviceUrl/internal/jobs/expire-orders" `
  --headers "X-Job-Token=$jobToken"
```

**6. Razorpay webhook.** In the Dashboard, go to Settings → Webhooks and add `https://<service-url>/api/v1/webhooks/razorpay` with events `payment.captured`, `order.paid` and `payment.failed`, using the webhook secret from step 2.

**7. Switch the frontend.** Set `orderServiceUrl` in `public/config.js` to the Cloud Run URL and run `firebase deploy --only hosting`. Until this is set, checkout keeps using the original flow.

**8. Lock down order data.** Once checkout goes through the service, only the service should write these collections. Add to `firestore.rules`:

```
match /orders/{orderId} {
  allow read: if request.auth != null
    && (resource.data.buyerId == request.auth.uid || request.auth.uid in resource.data.sellerIds);
  allow write: if false;
}
match /ledgerEntries/{entryId} {
  allow read: if request.auth != null && resource.data.sellerId == request.auth.uid;
  allow write: if false;
}
match /idempotencyKeys/{keyId} {
  allow read, write: if false;
}
match /webhookEvents/{eventId} {
  allow read, write: if false;
}
```

To test a payment end to end, use Razorpay's test card details: https://razorpay.com/docs/payments/payments/test-card-details/

## Code layout

```
src/main/java/com/kalasetu/orders/
├── api/          REST controllers, request/response types, error mapping
├── config/       Settings, Firebase, CORS and auth filter registration
├── domain/       Order, OrderStatus state machine, Money, ledger records
├── payment/      Razorpay client and signature verification
├── security/     Firebase ID token verification filter
├── service/      Order creation, payments, webhooks, expiry
└── store/        Storage interface and Firestore implementation
```
