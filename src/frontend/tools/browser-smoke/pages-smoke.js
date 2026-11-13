// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
// Executes the real server-rendered pages + real static JS inside jsdom against a faked /api backend.
const { JSDOM, ResourceLoader, VirtualConsole } = require('jsdom');

const BASE = process.env.BASE || 'http://localhost:18080';

class Loader extends ResourceLoader {
  fetch(url, options) {
    if (!url.startsWith(BASE)) return Promise.resolve(Buffer.from(''));
    return super.fetch(url, options);
  }
}

const products = {
  OLJCESPC7Z: { id: 'OLJCESPC7Z', name: 'Sunglasses', description: 'Sunglasses desc', picture: 'Sunglasses.jpg', priceUsd: { currencyCode: 'USD', units: 19, nanos: 990000000 }, categories: ['accessories'] },
  '66VCHSJNUP': { id: '66VCHSJNUP', name: 'Starsense Explorer', description: 'Telescope', picture: 'StarsenseExplorer.jpg', priceUsd: { currencyCode: 'USD', units: 349, nanos: 950000000 }, categories: ['telescopes'] },
  L9ECAV7KIM: { id: 'L9ECAV7KIM', name: 'Lens Cleaning Kit', description: 'Kit', picture: 'LensCleaningKit.jpg', priceUsd: { currencyCode: 'USD', units: 21, nanos: 950000000 }, categories: ['accessories'] },
};

function makeBackend(log, opts = {}) {
  const cart = { userId: 'x', items: [...(opts.cartItems || [])] };
  const json = (body, status = 200) =>
    Promise.resolve(new Response(body === undefined ? '' : JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } }));
  return async function fakeFetch(input, init = {}) {
    const url = new URL(typeof input === 'string' ? input : input.url, BASE);
    const method = (init.method || (input && input.method) || 'GET').toUpperCase();
    log.push(`${method} ${url.pathname}${url.search}`);
    const p = url.pathname;
    if (p.startsWith('/flagservice')) throw new TypeError('network down');
    if (p.startsWith('/images/')) return new Response('img', { status: 200 });
    if (p.startsWith('/otlp-http')) return new Response('', { status: 200 });
    if (p === '/api/currency') return json(['USD', 'EUR', 'JPY']);
    if (p === '/api/products') return json(Object.values(products));
    if (p.startsWith('/api/products/')) return json(products[p.split('/').pop()]);
    if (p === '/api/cart') {
      if (method === 'GET') return json({ userId: 'x', items: cart.items.map(i => ({ ...i, product: products[i.productId] })) });
      if (method === 'POST') {
        const body = JSON.parse(init.body);
        const ex = cart.items.find(i => i.productId === body.item.productId);
        if (ex) ex.quantity += body.item.quantity; else cart.items.push({ ...body.item });
        return json({ userId: body.userId, items: cart.items });
      }
      if (method === 'DELETE') { cart.items.length = 0; return new Response(null, { status: 204 }); }
    }
    if (p === '/api/data') return json([{ redirectUrl: '/product/OLJCESPC7Z', text: 'Buy sunglasses' }]);
    if (p === '/api/recommendations') return json(Object.values(products).concat([products.OLJCESPC7Z]));
    if (p === '/api/shipping') return json({ currencyCode: 'USD', units: 8, nanos: 990000000 });
    if (p === '/api/checkout') {
      const body = JSON.parse(init.body);
      log.push('CHECKOUT BODY ' + init.body);
      return json({
        orderId: 'order-123', shippingTrackingId: 'trk', shippingCost: { currencyCode: 'USD', units: 8, nanos: 990000000 },
        shippingAddress: body.address,
        items: cart.items.map(i => ({ cost: products[i.productId].priceUsd, item: { ...i, product: products[i.productId] } })),
      });
    }
    return new Response('not found', { status: 404 });
  };
}

async function load(path, opts = {}) {
  const html = await (await fetch(BASE + path)).text();
  const log = [];
  const errors = [];
  const vc = new VirtualConsole();
  vc.on('jsdomError', e => errors.push('jsdomError: ' + String(e.message).split('\n')[0]));
  vc.on('error', (...a) => errors.push('console.error: ' + a.join(' ').slice(0, 300)));
  vc.on('warn', (...a) => {});
  const dom = new JSDOM(html, {
    url: BASE + path,
    runScripts: 'dangerously',
    resources: new Loader(),
    pretendToBeVisual: true,
    virtualConsole: vc,
    beforeParse(window) {
      window.fetch = makeBackend(log, opts);
      window.Response = Response; window.Headers = Headers;
      window.AbortController = AbortController;
      window.Request = class extends Request { constructor(u, i) { super(new URL(u, BASE).href, i); } };
      window.TextEncoder = TextEncoder; window.TextDecoder = TextDecoder;
      window.URL.createObjectURL = () => 'blob:fake';
      window.URL.revokeObjectURL = () => {};
      if (opts.storage) for (const [k, v] of Object.entries(opts.storage)) window.localStorage.setItem(k, v);
    },
  });
  await new Promise(r => dom.window.addEventListener('load', r));
  await new Promise(r => setTimeout(r, opts.wait || 700));
  return { dom, doc: dom.window.document, log, errors, window: dom.window };
}

let failed = 0;
function check(name, cond, extra = '') {
  if (!cond) failed++;
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${cond ? '' : '  ' + extra}`);
}
const q = (doc, cy) => doc.querySelectorAll(`[data-cy="${cy}"]`);

(async () => {
  // ---- home ----
  let t = await load('/');
  check('home: no script errors', t.errors.length === 0, JSON.stringify(t.errors));
  check('home: telemetry bundle exposed', !!t.window.OtelDemoTelemetry);
  check('home: ENV present', t.window.ENV && t.window.ENV.NEXT_PUBLIC_OTEL_SERVICE_NAME === 'frontend-web');
  check('home: 3 product cards in list', q(t.doc, 'product-card').length === 3, String(q(t.doc, 'product-card').length));
  check('home: price text "$ 19.99"', [...q(t.doc, 'product-price')].some(e => e.textContent === '$ 19.99'), [...q(t.doc, 'product-price')].map(e => e.textContent).join('|'));
  check('home: session id shown', /^session-id: [0-9a-f-]{36}$/.test(q(t.doc, 'session-id')[0].textContent), q(t.doc, 'session-id')[0].textContent);
  check('home: currency options', [...t.doc.querySelectorAll('[data-cy="currency-switcher"] option')].map(o => o.value).join() === 'EUR,JPY,USD');
  check('home: currency select value USD', t.doc.querySelector('[data-cy="currency-switcher"]').value === 'USD');
  check('home: symbol $', t.doc.querySelector('.selected-currency').textContent === '$');
  check('home: fetched /api/products?currencyCode=USD', t.log.includes('GET /api/products?currencyCode=USD'), t.log.join('\n'));
  check('home: product image fetch with headers', t.log.some(l => l.startsWith('GET /images/products/')));
  const stored = JSON.parse(t.window.localStorage.getItem('session'));
  check('home: session persisted in localStorage', stored && stored.currencyCode === 'USD' && stored.userId.length === 36);
  // currency switch
  const sel = t.doc.querySelector('[data-cy="currency-switcher"]');
  sel.value = 'EUR';
  sel.dispatchEvent(new t.window.Event('change', { bubbles: true }));
  await new Promise(r => setTimeout(r, 400));
  check('home: currency persisted', JSON.parse(t.window.localStorage.getItem('session')).currencyCode === 'EUR');
  check('home: refetched products in EUR', t.log.includes('GET /api/products?currencyCode=EUR'));
  check('home: euro symbol', t.doc.querySelector('.selected-currency').textContent === '€');
  t.window.close();

  // corrupted session recovery
  t = await load('/', { storage: { session: 'not}{valid-json' } });
  const rec = JSON.parse(t.window.localStorage.getItem('session'));
  check('home: corrupted session recovered', rec && rec.userId && q(t.doc, 'session-id')[0].textContent.includes(rec.userId));
  t.window.close();

  // ---- product ----
  t = await load('/product/OLJCESPC7Z');
  check('product: no script errors', t.errors.length === 0, JSON.stringify(t.errors));
  check('product: detail exists', q(t.doc, 'product-detail').length === 1);
  check('product: picture/name/description/add', ['product-picture', 'product-name', 'product-description', 'product-add-to-cart', 'product-quantity'].every(c => q(t.doc, c).length === 1));
  check('product: name text', q(t.doc, 'product-name')[0].textContent === 'Sunglasses');
  check('product: picture bg url', q(t.doc, 'product-picture')[0].getAttribute('style').includes('/images/products/Sunglasses.jpg'));
  const recs = q(t.doc, 'recommendation-list')[0];
  check('product: 4 recommendations', recs && recs.querySelectorAll('[data-cy="product-card"]').length === 4);
  check('product: ad filled', t.doc.querySelector('[data-cy="ad"] a').getAttribute('href') === '/product/OLJCESPC7Z' && t.doc.querySelector('[data-cy="ad"] p').textContent === 'Buy sunglasses');
  check('product: ads fetched with contextKeys', t.log.includes('GET /api/data?contextKeys=accessories'), t.log.join('\n'));
  check('product: recs fetched w/ productIds+sessionId', t.log.some(l => /GET \/api\/recommendations\?productIds=OLJCESPC7Z&sessionId=[0-9a-f-]{36}&currencyCode=USD/.test(l)), t.log.join('\n'));
  // add to cart
  const qty = q(t.doc, 'product-quantity')[0];
  qty.value = '3';
  qty.dispatchEvent(new t.window.Event('change', { bubbles: true }));
  q(t.doc, 'product-add-to-cart')[0].click();
  await new Promise(r => setTimeout(r, 400));
  const post = t.log.find(l => l.startsWith('POST /api/cart'));
  check('product: POST /api/cart?currencyCode=USD', post === 'POST /api/cart?currencyCode=USD', post);
  check('product: navigation to /cart attempted', t.errors.some(e => e.includes('navigation')), JSON.stringify(t.errors));
  t.window.close();

  // missing picture
  {
    const html = await (await fetch(BASE + '/product/OLJCESPC7Z')).text();
    products.NOPIC = { ...products.OLJCESPC7Z, id: 'NOPIC', picture: undefined };
    t = await load('/product/NOPIC');
    check('product (no picture): no picture element', q(t.doc, 'product-picture').length === 0);
    check('product (no picture): no undefined image request', !t.log.some(l => l.includes('/images/products/undefined')));
    check('product (no picture): name still shown', q(t.doc, 'product-name').length === 1);
    t.window.close();
  }

  // ---- cart (with items) ----
  const items = [{ productId: 'OLJCESPC7Z', quantity: 2 }, { productId: 'L9ECAV7KIM', quantity: 1 }];
  t = await load('/cart', { cartItems: items, wait: 900 });
  check('cart: no script errors', t.errors.length === 0, JSON.stringify(t.errors));
  check('cart: item count badge 2', q(t.doc, 'cart-item-count')[0] && q(t.doc, 'cart-item-count')[0].textContent === '2');
  check('cart: 2 item rows', t.doc.querySelectorAll('.cart-item').length === 2);
  check('cart: place order button', q(t.doc, 'checkout-place-order').length === 1);
  check('cart: form fields', ['email', 'street_address', 'zip_code', 'city', 'state', 'country', 'credit_card_number', 'credit_card_expiration_month', 'credit_card_expiration_year', 'credit_card_cvv'].every(id => t.doc.getElementById(id)));
  check('cart: default form values', t.doc.getElementById('email').value === 'someone@example.com' && t.doc.getElementById('credit_card_number').value === '4432-8015-6152-0454' && t.doc.getElementById('credit_card_expiration_year').value === '2030' && t.doc.getElementById('credit_card_expiration_month').value === '1' && t.doc.getElementById('credit_card_cvv').value === '672');
  check('cart: shipping requested', t.log.some(l => l.startsWith('GET /api/shipping?itemList=')), t.log.join('\n'));
  const totals = [...t.doc.querySelectorAll('.cart-items-data-row')].map(e => e.textContent.replace(/\s+/g, ' ').trim());
  check('cart: shipping + total rows', totals[0] === 'Shipping$ 8.99' && totals[1] === 'Total$ 70.92', JSON.stringify(totals));
  check('cart: recommendations shown', q(t.doc, 'recommendation-list').length === 1);
  check('cart: ads still requested (as AdProvider did)', t.log.some(l => l.startsWith('GET /api/data?contextKeys=')));
  // dropdown
  q(t.doc, 'cart-icon')[0].click();
  check('cart: dropdown opens with 2 items', q(t.doc, 'cart-dropdown').length === 1 && q(t.doc, 'cart-dropdown-item').length === 2);
  check('cart: go to cart link', q(t.doc, 'cart-go-to-shopping').length === 1);
  t.doc.body.dispatchEvent(new t.window.MouseEvent('mousedown', { bubbles: true }));
  check('cart: dropdown closes on outside click', q(t.doc, 'cart-dropdown').length === 0);
  // place order
  t.doc.querySelector('.checkout-form').dispatchEvent(new t.window.Event('submit', { bubbles: true, cancelable: true }));
  await new Promise(r => setTimeout(r, 500));
  const co = t.log.find(l => l.startsWith('POST /api/checkout'));
  check('cart: POST /api/checkout?currencyCode=USD', co === 'POST /api/checkout?currencyCode=USD', co);
  const body = JSON.parse((t.log.find(l => l.startsWith('CHECKOUT BODY ')) || 'CHECKOUT BODY {}').slice(14));
  check('cart: checkout body shape', body.userId && body.email === 'someone@example.com' && body.userCurrency === 'USD' && body.address.streetAddress === '1600 Amphitheatre Parkway' && body.address.zipCode === '94043' && body.creditCard.creditCardNumber === '4432-8015-6152-0454' && String(body.creditCard.creditCardCvv) === '672', JSON.stringify(body));
  check('cart: navigation to checkout attempted', t.errors.some(e => e.includes('navigation')), JSON.stringify(t.errors));
  // quantity change
  const qsel = t.doc.querySelector('.cart-item .select');
  qsel.value = '5';
  qsel.dispatchEvent(new t.window.Event('change', { bubbles: true }));
  await new Promise(r => setTimeout(r, 500));
  check('cart: quantity change posts the delta (+3)', t.log.some(l => l.startsWith('POST /api/cart')) && true);
  // empty cart
  t.doc.querySelector('.empty-cart-button').click();
  await new Promise(r => setTimeout(r, 600));
  check('cart: DELETE /api/cart', t.log.some(l => l === 'DELETE /api/cart'));
  check('cart: empty message shown', t.doc.body.textContent.includes('Your shopping cart is empty!'));
  t.window.close();

  // ---- cart (empty) ----
  t = await load('/cart');
  check('empty cart: message + continue shopping', t.doc.body.textContent.includes('Items you add to your shopping cart will appear here.') && t.doc.querySelector('.cart-empty-container a[href="/"]'));
  t.window.close();

  // ---- checkout ----
  const order = {
    orderId: 'order-123', shippingTrackingId: 't', shippingCost: { currencyCode: 'USD', units: 8, nanos: 990000000 },
    shippingAddress: { streetAddress: '1600 Amphitheatre Parkway', city: 'Mountain View', state: 'CA', country: 'United States', zipCode: '94043' },
    items: [
      { cost: products.OLJCESPC7Z.priceUsd, item: { productId: 'OLJCESPC7Z', quantity: 2, product: products.OLJCESPC7Z } },
      { cost: products.L9ECAV7KIM.priceUsd, item: { productId: 'L9ECAV7KIM', quantity: 1, product: products.L9ECAV7KIM } },
    ],
  };
  t = await load('/cart/checkout/order-123?order=' + encodeURIComponent(JSON.stringify(order)));
  check('checkout: no script errors', t.errors.length === 0, JSON.stringify(t.errors));
  check('checkout: 2 checkout-item hooks', q(t.doc, 'checkout-item').length === 2);
  check('checkout: complete message + order id', t.doc.body.textContent.includes('Your order is complete!') && t.doc.body.textContent.includes('order-123'));
  check('checkout: address lines', t.doc.body.textContent.includes('Mountain View, CA 94043'));
  const sum = [...t.doc.querySelectorAll('.checkout-summary-row,.checkout-total-row')].map(e => e.textContent.replace(/\s+/g, ' ').trim());
  check('checkout: shipping + total', sum[0] === 'Shipping:$ 8.99' && sum[1] === 'Total:$ 70.92', JSON.stringify(sum));
  check('checkout: ad + recommendations', t.doc.querySelector('[data-cy="ad"] p').textContent === 'Buy sunglasses' && q(t.doc, 'recommendation-list').length === 1);
  t.window.close();

  console.log(failed ? `\n${failed} FAILED` : '\nALL PASSED');
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
