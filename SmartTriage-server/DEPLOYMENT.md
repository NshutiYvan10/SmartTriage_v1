# SmartTriage — Deployment & Configuration

This runbook covers the **backend** (`SmartTriage-server`). Configuration is driven
entirely by environment variables — see [`.env.example`](.env.example) for the
copy-paste template. No secret is committed to source control.

## Profiles

The active profile is chosen by `SPRING_PROFILES_ACTIVE` (committed default: `dev`).

| Profile   | Database                                   | JWT secret                          | Logging | Actuator |
|-----------|--------------------------------------------|-------------------------------------|---------|----------|
| `dev`     | `localhost:5432/smarttriage_dev` (fixed)   | dev-only fake key (built-in)        | DEBUG   | health, info, metrics |
| `staging` | `DATABASE_*` env (**required**)            | `JWT_SECRET` env (**required**)     | INFO    | health, info, metrics |
| `prod`    | `DATABASE_*` env (**required**)            | `JWT_SECRET` env (**required**)     | INFO/WARN | health, info (details: never) |

> The committed default profile is `dev`. **You must set `SPRING_PROFILES_ACTIVE=prod`
> (or `staging`) for a real deployment**, otherwise the app starts with local-dev
> settings and a deliberately-fake JWT key.

## Required environment variables

### Every non-dev environment (`staging`, `prod`) — the app will NOT start without these
| Variable             | Purpose                                                            |
|----------------------|--------------------------------------------------------------------|
| `SPRING_PROFILES_ACTIVE` | Set to `prod` or `staging`.                                    |
| `DATABASE_URL`       | JDBC URL, e.g. `jdbc:postgresql://db-host:5432/smarttriage`.        |
| `DATABASE_USERNAME`  | DB user.                                                            |
| `DATABASE_PASSWORD`  | DB password.                                                       |
| `JWT_SECRET`         | Base64, decodes to ≥ 32 bytes (256 bits). `openssl rand -base64 48`. |

### Optional (safe defaults; affect invitation email only)
| Variable        | Default              | Notes                                              |
|-----------------|----------------------|----------------------------------------------------|
| `SMTP_HOST`     | `smtp.gmail.com`     |                                                    |
| `SMTP_PORT`     | `587`                |                                                    |
| `SMTP_USERNAME` | _(empty)_            | Sender account.                                    |
| `SMTP_PASSWORD` | _(empty)_            | Gmail → 16-char **App Password**. Empty ⇒ email disabled. |
| `SMTP_FROM`     | _(empty)_            | From address on invitation/activation emails.      |
| `FRONTEND_URL`  | `http://localhost:5173` | Origin used in email links.                     |

## Secrets — handling & rotation

- **Never commit a real secret.** `JWT_SECRET` and `SMTP_PASSWORD` have no committed
  fallback in the shared config; supply them via the environment.
- **`.env` is gitignored**; only this `.env.example` template is tracked.
- **Mail is decoupled from health** (`management.health.mail.enabled=false`): an
  unreachable/unauthenticated SMTP server must never flip `/actuator/health` to DOWN
  (which would fail a Kubernetes liveness/readiness probe and pull a working clinical
  app out of rotation). Invitation email is auxiliary; patient care is not.

### ⚠️ If a secret has ever been committed
Removing it from the file does **not** neutralize it — it remains in git history.
Rotate it at the source:
- **Gmail App Password:** revoke + regenerate at
  <https://myaccount.google.com> → Security → App passwords, then set the new value
  as `SMTP_PASSWORD`.
- **`JWT_SECRET`:** generate a fresh one (`openssl rand -base64 48`) and set it in
  every non-dev environment. Rotating it invalidates all existing access/refresh
  tokens (users must log in again) — expected.

## Running

```bash
# Local development (uses application-dev.properties; needs local Postgres
# smarttriage_dev on localhost:5432, user postgres / password)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Production (env-driven). Feed variables via your platform; e.g. plain shell:
set -a; source .env; set +a
SPRING_PROFILES_ACTIVE=prod java -jar target/SmartTriage-server-*.jar
```

Flyway migrates the schema on startup; `spring.jpa.hibernate.ddl-auto=validate`
verifies the entity mappings against the migrated schema (never mutates it).

## Frontend

`SmartTriage_Frontend_V6` is a static Vite build that calls the API at the relative
path `/api/v1` and connects the WebSocket at `/ws` — it uses **no build-time
environment variables**. Serve the built assets behind a reverse proxy that forwards
`/api` and `/ws` to this backend (so the browser and API share an origin).
