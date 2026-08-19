# Creator Link Store API

Spring Boot API for the sectioned creator admin, public storefront, products, customers, orders, analytics, integrations, and automation metadata.

```bash
docker compose up -d db
DB_USER=creator DB_PASSWORD=creator mvn spring-boot:run
```

It listens on port 8080 and its inferred PostgreSQL schema is initialized from `src/main/resources/schema.sql`. A clean database seeds the deterministic `alex` demo (its password is a random, intentionally-unusable value — register a real account via `POST /api/auth/register` and log in through `POST /api/auth/login` to get a usable session).

India launch defaults are `INR`, Razorpay as the preferred strategy, and both payments and Instagram disabled. `GET /api/v1/payments/config` and `GET /api/v1/integrations/instagram/config` report safe readiness without secrets. Provider modes are `disabled`, `test`, and `live`; use test credentials locally and store deployed secrets in AWS Secrets Manager. Checkout amounts are always loaded server-side and expressed as integer paise in `*_subunits` fields.

## Authentication

`POST /api/auth/register` creates a creator (BCrypt-hashed password). `POST /api/auth/login` (handle-or-email + password) issues an httpOnly session cookie backed by the `sessions` table (30-day opaque token, hashed at rest — no JWT, no extra secret to manage). `POST /api/auth/logout` revokes it, `GET /api/auth/me` returns the current profile. Every `/api/v1/**` route requires this session except the public/webhook/checkout paths a buyer or payment provider needs to hit anonymously — see `identity/AuthInterceptor.java` for the exact allowlist. There is no client-supplied `creatorId` trusted anywhere anymore; it's always resolved server-side from the session.

## Product types

All 8 types (`digital-download`, `lead-magnet`, `fulfillment`, `meeting`, `webinar`, `community`, `membership`, `course`) have real, working flows end to end — not just an accepted label. Each paid order grants a buyer an `entitlements` row with a random access token; `GET /api/buyer/access/{token}` (and type-specific sub-routes: file downloads, curriculum, booking, webinar join link, membership status) serves the purchased content with no buyer login required. See `CreatorStoreApplication.java` for the full endpoint list and `commerce/OrderFulfillmentService.java` for how a webhook-confirmed payment turns into an order, customer, and entitlement.

## Deployment

For the complete containerized dev workflow (three containers, smoke test), start with the [infrastructure repository](https://github.com/kvsram/creator-link-store-infrastructure). For an actual production deployment, see [`infrastructure/production/README.md`](https://github.com/kvsram/creator-link-store-infrastructure/tree/main/production) — a simple single-VM setup (Caddy for TLS + a managed Postgres), not the full multi-region AWS/EKS path.

## Regional Kubernetes deployment

`deploy/overlays/region-a`, `region-b`, and `region-c` target three independent Kubernetes clusters. Each deploys one API replica initially, with an HPA that can grow to three replicas in that region. The application remains stateless; PostgreSQL is an external managed service, not a Pod in every cluster.

Create the `creator-store-db` Secret in each cluster using its private managed-PostgreSQL endpoint. It is deliberately not generated from the placeholder template by Kustomize. For the first low-scale phase, use one active writer region and keep another region warm; the current API has no read/write datasource split and arbitrary cross-region writes are unsafe. Do not commit a real database password: create the Secret through External Secrets or an equivalent cloud secret-manager integration.

The integration ConfigMap deliberately keeps `PAYMENTS_MODE` and `INSTAGRAM_MODE` disabled. Create `creator-store-runtime-secrets` through External Secrets before switching a stage to `test`. `deploy/base/integration-secret.template.yaml` documents names only and is intentionally excluded from Kustomize.

GitHub Actions builds an immutable GHCR image on every `main` push. Manual promotion uses protected GitHub Environments `dev`, `preprod`, and `prod`, short-lived AWS OIDC credentials, and a self-hosted runner labeled `aws-private` that can reach the private EKS API. Configure the regional variables documented in the infrastructure repository and trigger **Deploy backend** with the exact 40-character image commit SHA. No kubeconfig is stored in GitHub.
