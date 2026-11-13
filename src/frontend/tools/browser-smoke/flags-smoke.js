// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
const http = require('http');
const fs = require('fs');
const { JSDOM } = require('jsdom');
const ROOT = require('path').join(__dirname, '../../src/main/resources/static/assets/js') + '/';
let delay = 5000; const reqs = [];
function frame(obj, flags = 0) { const p = Buffer.from(JSON.stringify(obj)); const b = Buffer.alloc(5 + p.length); b[0] = flags; b.writeUInt32BE(p.length, 1); p.copy(b, 5); return b; }
let streamRes;
const srv = http.createServer((req, res) => {
  let body = []; req.on('data', c => body.push(c));
  req.on('end', () => {
    body = Buffer.concat(body);
    reqs.push(req.method + ' ' + req.url + ' ' + req.headers['content-type']);
    if (req.url.endsWith('/ResolveAll')) {
      reqs.push('ctx ' + body.toString());
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ flags: { imageSlowLoad: { reason: 'STATIC', variant: '5sec', doubleValue: delay }, other: { boolValue: false, variant: 'off' } } }));
    }
    if (req.url.endsWith('/EventStream')) {
      reqs.push('envelope ' + body.length + ' ' + body.toString('hex'));
      streamRes = res;
      res.writeHead(200, { 'content-type': 'application/connect+json' });
      res.write(frame({ type: 'provider_ready' }));
      return;
    }
    res.writeHead(404); res.end();
  });
});
srv.listen(18081, async () => {
  const dom = new JSDOM('<!doctype html><body>', { url: 'http://localhost:18081/', runScripts: 'outside-only' });
  const w = dom.window;
  w.fetch = (u, i) => fetch(u, i); w.TextEncoder = TextEncoder; w.TextDecoder = TextDecoder; w.AbortController = AbortController;
  w.eval(fs.readFileSync(ROOT + 'session.js', 'utf8'));
  w.eval(fs.readFileSync(ROOT + 'flags.js', 'utf8'));
  const F = w.OtelDemoFlags;
  let changes = 0; F.onChange(() => changes++);
  console.log('before ready ->', F.getNumber('imageSlowLoad', 0));
  await new Promise(r => setTimeout(r, 500));
  console.log('after provider_ready ->', F.getNumber('imageSlowLoad', 0), 'changes', changes);
  delay = 10000; streamRes.write(frame({ type: 'configuration_change' }));
  await new Promise(r => setTimeout(r, 500));
  console.log('after configuration_change ->', F.getNumber('imageSlowLoad', 0), 'changes', changes);
  console.log(reqs.join('\n'));
  const ok = F.getNumber('imageSlowLoad', 0) === 10000;
  console.log(ok ? 'FLAGS OK' : 'FLAGS FAIL');
  process.exit(ok ? 0 : 1);
});
