# Email Service

The Email service "sends" an email to the customer with their order details. It
expects a JSON payload on `POST /send_order_confirmation` like:

```json
{
  "email": "some.address@website.com",
  "order": "<serialized order protobuf, as JSON with snake_case field names>"
}
```

and answers an empty `200`. The email is rendered from the ERB template in
[`src/main/resources/templates/confirmation.erb`](src/main/resources/templates/confirmation.erb)
and "delivered" to an in-memory outbox, exactly as the original Ruby service did
with Pony's `:test` delivery. Nothing is sent over a network. The
`emailMemoryLeak` feature flag pads the body and stops the outbox from being
cleared, which makes the service leak memory on purpose.

It is a Spring Boot 4 application (Java 21) using Spring Web MVC. The telemetry
comes from the OpenTelemetry Spring Boot starter (no Java agent) and feature
flags are evaluated with the OpenFeature Java SDK and the flagd provider. See
[PORTING-NOTES.md](PORTING-NOTES.md) for how the behavior maps to the original
Ruby/Sinatra service.

Environment variables: `EMAIL_PORT` (default `6060`), `FLAGD_HOST`,
`FLAGD_PORT`, `FLAGD_TLS`, `OTEL_*`.

## Local Build

Requires JDK 21 or newer:

```sh
./gradlew build
```

## Docker Build

From the root directory, run:

```sh
docker compose build email
```
