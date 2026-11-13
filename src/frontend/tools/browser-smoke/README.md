# Browser smoke tests (maintainer tools, not part of the Gradle build)

Two jsdom-based scripts that exercise the real server-rendered pages and the real static JavaScript
against a faked `/api/*` backend. They are a cheap stand-in for the Cypress suite when no full stack is
available; they do not replace it.

```sh
npm install
# 1. start the app (backends may be down, /api is faked in the script):
#      PORT=18080 java -jar ../../build/libs/frontend.jar
# 2. run
BASE=http://localhost:18080 node pages-smoke.js     # home / product / cart / checkout flows, DOM hooks
node flags-smoke.js                                   # flagd Connect client against a mock flagd
```
