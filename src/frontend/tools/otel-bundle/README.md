# Browser telemetry bundle (maintainer tool, not part of the Java build)

`src/main/resources/static/assets/js/vendor/frontend-otel.js` is a pre-built, minified IIFE bundle of the
OpenTelemetry JS browser SDK, configured by `entry.js` (a port of the original
`utils/telemetry/FrontendTracer.ts` + `SessionIdProcessor.ts`). It is committed so that the Gradle/Docker
build needs no Node step. To regenerate it (for example to bump the OpenTelemetry JS versions, which are
pinned to the ones of the original `package.json`):

```sh
cd src/frontend/tools/otel-bundle
npm install
npx esbuild entry.js --bundle --minify --format=iife --target=es2020 --legal-comments=none \
  --outfile=../../src/main/resources/static/assets/js/vendor/frontend-otel.js
```
