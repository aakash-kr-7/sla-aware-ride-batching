# Local development

Requires JDK 17+ and Docker with Compose. Maven is supplied by the wrapper. First use downloads Maven dependencies and the PostgreSQL image.

## Database and application

1. Copy `.env.example` to `.env` and supply your own `DB_PASSWORD` and `JWT_SECRET` (at least 32 characters). Do not commit the populated file.
2. To create an initial admin, set `LOCAL_ADMIN_ENABLED=true` and supply `LOCAL_ADMIN_USERNAME` and `LOCAL_ADMIN_PASSWORD`. Bootstrap runs only with the local profile and an empty account table. Ordinary startup creates no accounts.
3. Run `docker compose up -d --wait`.
4. Export environment values before running Spring Boot; Compose reads `.env`, but Spring Boot does not load it automatically.

PowerShell, from the repository root:

```powershell
Copy-Item .env.example .env
# Edit .env and supply your local values before proceeding.
docker compose up -d --wait
Get-Content .env | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
./mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

Do not repeat the copy over an already configured `.env`. The parser above expects simple unquoted `NAME=value` lines. In a POSIX shell, export the configured values and run `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.

The normal server port is 8080. Open `http://127.0.0.1:8080/swagger-ui/index.html`, or inspect `http://127.0.0.1:8080/actuator/health`. The root `/` has no homepage. Flyway owns the schema; Hibernate validates it.

## Data and access boundaries

Startup creates no service zones, employee records, drivers or employee login accounts. Provisioning those records is currently a database/repository operation; there are no provisioning HTTP endpoints. The admin bootstrap only creates a login account, not the rest of the business data. Integration tests create their own fixtures in disposable databases.

Log in using `POST /auth/login` with your supplied credentials. The response contains `token`; Swagger's Authorize dialog accepts that token without an extra `Bearer` prefix. Booking create/list/cancel also require `X-Employee-Id`, and non-admin callers must match their account's employee association. See [API contract](api-design.md).

Structured domain errors differ from container-level HTTP parsing errors. If Chrome shows only HTTP 400 while direct requests work, inspect the application log. Oversized browser headers can be caused by local-host cookies; trying `127.0.0.1` or an Incognito window checks that possibility.

## Tests and shutdown

```powershell
./mvnw.cmd --batch-mode clean verify
```

Tests use PostgreSQL Testcontainers and do not require a populated `.env`. This command compiles, runs all tests and packages the executable application JAR under `target/`. Use `./mvnw` outside Windows. CI runs the same clean build on Java 21; the source target is Java 17.

Stop the application with Ctrl+C and PostgreSQL with `docker compose stop`. `docker compose down` preserves the named volume. `docker compose down --volumes` deletes that local database; use it only when intentionally discarding its data.
