# Flagd-ui

This application provides a user interface for configuring the feature
flags of the flagd service.

It is a Spring Boot 4 application (Java 21): Spring Web MVC serves the pages
and the REST API, Thymeleaf renders the pages, and a small plain JavaScript
file (`src/main/resources/static/assets/js/app.js`) provides the interaction
that Phoenix LiveView provided in the previous version of this service.

The telemetry comes from the OpenTelemetry Spring Boot starter, so no Java agent
is involved.

## Running the application

The application can be run with the rest of the demo using the documented
[docker compose or make commands](https://opentelemetry.io/docs/demo/#running-the-demo).

The front proxy (Envoy) exposes the UI under `/feature` and strips that prefix,
so the application itself serves from `/`. Generated links use the prefix
configured by `FLAGD_UI_ROOT_PATH` (default `/feature`).

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `FLAGD_UI_PORT` | `4000` | HTTP port |
| `FLAGD_UI_STORAGE_FILE_PATH` | `/app/data/demo.flagd.json` | flagd configuration file that is read and rewritten (compose mounts `./src/flagd` at `/app/data`) |
| `FLAGD_UI_ROOT_PATH` | `/feature` | public path prefix used in generated links |
| `OTEL_*` | | standard OpenTelemetry SDK variables, `OTEL_EXPORTER_OTLP_PROTOCOL` defaults to `http/protobuf` |

`SECRET_KEY_BASE` and `PHX_HOST`, which compose still passes, were Phoenix
settings and are not used any more.

## Local development

Requires JDK 21 or newer.

* Create a `data` folder: `mkdir data`.
* Copy [../flagd/demo.flagd.json](../flagd/demo.flagd.json) to `./data/demo.flagd.json`
  * `cp ../flagd/demo.flagd.json ./data/demo.flagd.json`
* Start the application with the local file and no path prefix:
  `FLAGD_UI_STORAGE_FILE_PATH=./data/demo.flagd.json FLAGD_UI_ROOT_PATH=/ ./gradlew bootRun`

Now you can visit `localhost:4000` from your browser. Run the tests with
`./gradlew test` (they use a copy of `../flagd/demo.flagd.json`).

## Rebuilding the CSS

`src/main/resources/static/assets/css/app.css` is the compiled Tailwind 4 /
daisyUI stylesheet. Its source is `assets/css/app.css` together with the vendored
daisyUI and Heroicons plugins in `assets/vendor`, unchanged from the Phoenix
version apart from the `@source` paths. To rebuild after changing classes in the
templates or in `app.js`:

```sh
git clone --depth 1 --branch v2.2.0 https://github.com/tailwindlabs/heroicons deps/heroicons
tailwindcss --input=assets/css/app.css --output=src/main/resources/static/assets/css/app.css --minify
```

(The heroicons checkout and the Tailwind 4 CLI are only needed for this step.)

## Scheduler

The `Scheduler` tab automates failure scenarios, so that an incident appears and
resolves on its own while the demo is left running. This is useful when
presenting a backend that shows issues at a "recent" point in time.

Each interval the scheduler activates up to a configured number of distinct
randomly picked flags. Every activation gets its own hold duration between the
configured minimum and maximum, and its own offset, so that the whole activation
fits inside the interval. When a hold expires that flag is set back to its
resting variant, the flag's own configured default, which is `off` for every
failure scenario.

The following can be configured:

* **Interval**: how often activations happen.
* **Minimum and maximum duration**: bounds on how long a flag stays active. A
  random value in between is picked for each activation. The maximum has to fit
  within the interval.
* **Flags at once**: how many distinct flags may be activated per interval. A
  flag is never picked twice in the same interval, and asking for more than the
  number of selected flags simply activates all of them.
* **Flags**: which flag variants may be picked, chosen one variant at a time. A
  flag with graded variants can take part with only some of them, so
  `cartFailure` can be scheduled at `10%` and `25%` while `100%` is left out.
  A flag is offered when it has a resting variant, its configured default, to
  return to and at least one other variant to switch to. A flag whose metadata
  has `schedulable: false` is never offered.

  A flag is picked first and one of its selected variants second, so a flag with
  many variants is no more likely to be chosen than a flag with one.
* **Seed**: makes the sequence of picks reproducible. When left empty a seed is
  generated and reported back, so a run can be repeated later. (The same seed
  gives the same picks within this Java service; they differ from what the
  Elixir version picked for the same seed.)

Stopping the scheduler reverts any flags that are still being held active.

## Programmatic use through the API

This service exposes a REST API to ease its usage in a programmatic way for
power users.

You can read the current configuration using this HTTP call:

```json
$ curl localhost:8080/feature/api/read | jq

{
  "flags": {
    "adFailure": {
      "defaultVariant": "off",
      "description": "Fail ad service",
      "state": "ENABLED",
      "variants": {
        "off": false,
        "on": true
      }
    },
    "adHighCpu": {
      "defaultVariant": "off",
      "description": "Triggers high cpu load in the ad service",
      "state": "ENABLED",
      "variants": {
        "off": false,
        "on": true
      }
    },
    "adManualGc": {
      "defaultVariant": "off",
      "description": "Triggers full manual garbage collections in the ad service",
      "state": "ENABLED",
      "variants": {
        "off": false,
        "on": true
      }
    },
    ...
  }
}
```

You can also write a new settings file by sending a new configuration inside
the `data` field of a POST request body.

Bear in mind that _all_ the data will be rewritten by this write operation.

```sh
$ curl --header "Content-Type: application/json" \
  --request POST \
  --data '{"data": {"$schema":"https://flagd.dev/schema/v0/flags.json","flags":{"adFailure":{"defaultVariant":"on","description":"Fail ad service","state":"ENABLED","variants":{"off":false,"on":true}}...' \
  http://localhost:8080/feature/api/write
```

In addition to the `/read` and `/write` endpoint, we also offer these endpoint
to stay compatible with the old version of Flagd-ui:

* `/read-file` (`GET`)
* `/write-to-file` (`POST`)

A request without a `data` field gets `400` with `{"errors":{"detail":"Bad Request"}}`.

## Internal endpoints used by the pages

The pages call a few small endpoints below `/ui` (flag changes, the advanced
editor's save, the scheduler's start/stop and status fragments, and a version
poll for the basic page). They are not a stable API. They have no counterpart in
the Phoenix version, where LiveView carried the same events over its websocket.
