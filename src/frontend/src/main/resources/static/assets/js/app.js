// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

// Interactive layer of the storefront. The server renders each page's shell (layout, header, footer,
// structure); this script renders the dynamic parts from the /api/* routes. It is a vanilla-JS port of
// the React code of the original frontend (pages/*, components/*, providers/*, gateways/Api.gateway.ts)
// and produces the same DOM structure, ids, data-cy hooks and form field names.
(function () {
  'use strict';

  const Session = window.OtelDemoSession;
  const getSymbolFromCurrency = window.getSymbolFromCurrency;
  const Flags = window.OtelDemoFlags;

  const session = Session.getSession();
  const userId = session.userId;

  const state = {
    currency: session.currencyCode,
    currencyCodes: [],
    cart: { userId: '', items: [] },
  };

  // Callbacks of the current page, run after the cart or the currency changed
  const pageHooks = { onCartChange: () => {}, onCurrencyChange: () => {} };

  // ---------------------------------------------------------------------------------------------
  // DOM helpers
  // ---------------------------------------------------------------------------------------------

  function h(tag, props, ...children) {
    const el = document.createElement(tag);
    Object.entries(props || {}).forEach(([key, value]) => {
      if (value === undefined || value === null || value === false) {
        return;
      }
      if (key === 'class') {
        el.className = value;
      } else if (key === 'dataCy') {
        el.setAttribute('data-cy', value);
      } else if (key.startsWith('on') && typeof value === 'function') {
        el.addEventListener(key.slice(2).toLowerCase(), value);
      } else if (key === 'style') {
        el.style.cssText = value;
      } else {
        el.setAttribute(key, value === true ? '' : String(value));
      }
    });
    append(el, children);
    return el;
  }

  function append(el, children) {
    children.forEach(child => {
      if (Array.isArray(child)) {
        append(el, child);
      } else if (child !== null && child !== undefined && child !== false) {
        el.appendChild(child instanceof Node ? child : document.createTextNode(String(child)));
      }
    });
  }

  const $ = selector => document.querySelector(selector);

  // ---------------------------------------------------------------------------------------------
  // API gateway (gateways/Api.gateway.ts + utils/Request.ts)
  // ---------------------------------------------------------------------------------------------

  async function request({ url = '', method = 'GET', body, queryParams = {}, headers = { 'content-type': 'application/json' } }) {
    const response = await fetch(`${url}?${new URLSearchParams(queryParams).toString()}`, {
      method,
      body: body ? JSON.stringify(body) : undefined,
      headers,
    });

    const responseText = await response.text();
    let data;
    try {
      data = responseText ? JSON.parse(responseText) : undefined;
    } catch (err) {
      if (response.ok) {
        throw err;
      }
      data = undefined;
    }

    if (!response.ok) {
      throw new Error((data && data.error) || `Request failed with status ${response.status}`);
    }

    return data;
  }

  // Extends every API call so it carries session.id / enduser.id as W3C baggage
  function withBaggage(fn) {
    const telemetry = window.OtelDemoTelemetry;
    if (!telemetry) {
      return fn();
    }
    const { context, propagation } = telemetry;
    const baggage = propagation.getActiveBaggage() || propagation.createBaggage();
    const newBaggage = baggage.setEntry('session.id', { value: userId }).setEntry('enduser.id', { value: userId });
    const newContext = propagation.setBaggage(context.active(), newBaggage);
    return context.with(newContext, fn);
  }

  const basePath = '/api';

  const ApiGateway = {
    getCart: currencyCode =>
      withBaggage(() => request({ url: `${basePath}/cart`, queryParams: { sessionId: userId, currencyCode } })),
    addCartItem: ({ currencyCode, ...item }) =>
      withBaggage(() =>
        request({ url: `${basePath}/cart`, body: { item, userId }, queryParams: { currencyCode }, method: 'POST' })
      ),
    emptyCart: () => withBaggage(() => request({ url: `${basePath}/cart`, method: 'DELETE', body: { userId } })),
    getSupportedCurrencyList: () => withBaggage(() => request({ url: `${basePath}/currency` })),
    getShippingCost: (itemList, currencyCode, address) =>
      withBaggage(() =>
        request({
          url: `${basePath}/shipping`,
          queryParams: {
            itemList: JSON.stringify(itemList.map(({ productId, quantity }) => ({ productId, quantity }))),
            currencyCode,
            address: JSON.stringify(address),
          },
        })
      ),
    placeOrder: ({ currencyCode, ...order }) =>
      withBaggage(() =>
        request({ url: `${basePath}/checkout`, method: 'POST', queryParams: { currencyCode }, body: order })
      ),
    listProducts: currencyCode =>
      withBaggage(() => request({ url: `${basePath}/products`, queryParams: { currencyCode } })),
    getProduct: (productId, currencyCode) =>
      withBaggage(() => request({ url: `${basePath}/products/${productId}`, queryParams: { currencyCode } })),
    listRecommendations: (productIds, currencyCode) =>
      withBaggage(() =>
        request({ url: `${basePath}/recommendations`, queryParams: { productIds, sessionId: userId, currencyCode } })
      ),
    listAds: contextKeys => withBaggage(() => request({ url: `${basePath}/data`, queryParams: { contextKeys } })),
  };

  // ---------------------------------------------------------------------------------------------
  // Shared components
  // ---------------------------------------------------------------------------------------------

  /** components/ProductPrice */
  function productPrice(price) {
    const { units = 0, currencyCode, nanos = 0 } = price || {};
    const symbol = getSymbolFromCurrency(currencyCode) || state.currency;
    const total = units + nanos / 1000000000;
    return h('span', { dataCy: 'product-price' }, `${symbol} ${total.toFixed(2)}`);
  }

  const defaultMoney = () => ({ units: 0, currencyCode: 'USD', nanos: 0 });

  /** components/Select */
  function selectBox({ dataCy, value, onChange }, options) {
    const select = h(
      'select',
      { class: 'select', dataCy, onChange },
      options.map(option => h('option', { value: option }, option))
    );
    select.value = String(value);
    return h('div', { class: 'select-container' }, select, h('img', { class: 'select-arrow', src: '/icons/Chevron.svg', alt: 'select' }));
  }

  const quantityOptions = new Array(10).fill(0).map((_, i) => i + 1);

  // --- ProductCard: the image is fetched with the envoy fault-delay header driven by the
  // `imageSlowLoad` feature flag, exactly like components/ProductCard/ProductCard.tsx ---

  const cardImages = new Set();

  function loadCardImage(handle) {
    if (handle.cancel) {
      handle.cancel();
      handle.cancel = null;
    }
    if (!handle.picture) {
      handle.element.style.backgroundImage = '';
      return;
    }
    const imageSlowLoad = Flags ? Flags.getNumber('imageSlowLoad', 0) : 0;
    const controller = new AbortController();
    let objectUrl = null;
    let cancelled = false;
    const headers = new Headers();
    headers.append('x-envoy-fault-delay-request', imageSlowLoad.toString());
    headers.append('Cache-Control', 'no-cache');
    const requestInfo = new Request('/images/products/' + handle.picture, {
      method: 'GET',
      headers,
      signal: controller.signal,
    });
    fetch(requestInfo)
      .then(res => res.blob())
      .then(blob => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        handle.element.style.backgroundImage = `url("${objectUrl}")`;
      })
      .catch(err => {
        if (!cancelled && err.name !== 'AbortError') {
          handle.element.style.backgroundImage = '';
        }
      });
    handle.cancel = () => {
      cancelled = true;
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }

  if (Flags) {
    // Re-evaluate the delay when the flag changes (provider ready / configuration change)
    let lastValue = Flags.getNumber('imageSlowLoad', 0);
    Flags.onChange(() => {
      const value = Flags.getNumber('imageSlowLoad', 0);
      if (value === lastValue) return;
      lastValue = value;
      cardImages.forEach(handle => {
        if (handle.element.isConnected) {
          loadCardImage(handle);
        } else {
          cardImages.delete(handle);
        }
      });
    });
  }

  function productCard(product) {
    const { id, picture, name, priceUsd = defaultMoney() } = product;
    const image = h('div', { class: 'product-image' });
    const handle = { element: image, picture, cancel: null };
    cardImages.add(handle);
    loadCardImage(handle);

    return h(
      'a',
      { class: 'product-link', href: `/product/${id}` },
      h(
        'div',
        { class: 'product-card', dataCy: 'product-card' },
        image,
        h('div', {}, h('p', { class: 'product-name' }, name), h('p', { class: 'product-price' }, productPrice(priceUsd)))
      )
    );
  }

  function recommendations(recommendedProductList) {
    if (!recommendedProductList || recommendedProductList.length === 0) {
      return null;
    }
    return h(
      'section',
      { class: 'recommendations', dataCy: 'recommendation-list' },
      h('div', { class: 'recommendations-title-container' }, h('h3', { class: 'recommendations-title' }, 'You May Also Like')),
      h('div', { class: 'recommendations-list' }, recommendedProductList.map(productCard))
    );
  }

  function renderAd(adList) {
    const { text, redirectUrl } = adList[Math.floor(Math.random() * adList.length)] || { text: '', redirectUrl: '' };
    const section = $('#ad');
    if (!section) return;
    const link = section.querySelector('a');
    link.setAttribute('href', redirectUrl);
    link.querySelector('p').textContent = text;
  }

  /** providers/Ad.provider.tsx: ads for the context keys and recommendations for the product ids. */
  function loadAdData({ productIds, contextKeys }) {
    const ads =
      contextKeys.length === 0 ? Promise.resolve([]) : ApiGateway.listAds(contextKeys).catch(logError);
    const recs = ApiGateway.listRecommendations(productIds, state.currency).catch(logError);
    return Promise.all([ads, recs]).then(([adList, recommendedProductList]) => ({
      adList: adList || [],
      recommendedProductList: recommendedProductList || [],
    }));
  }

  function logError(error) {
    console.error(error);
    return undefined;
  }

  // ---------------------------------------------------------------------------------------------
  // Header: currency switcher, cart icon and cart dropdown
  // ---------------------------------------------------------------------------------------------

  const header = {
    dropdownOpen: false,

    init() {
      const select = $('.currency-select');
      select.addEventListener('change', event => {
        const currencyCode = event.target.value;
        state.currency = currencyCode;
        Session.setSessionValue('currencyCode', currencyCode);
        header.renderCurrency();
        loadCart().then(() => {});
        pageHooks.onCurrencyChange();
      });
      $('.cart-icon').addEventListener('click', () => {
        header.dropdownOpen = true;
        header.renderCart();
      });
      document.addEventListener('mousedown', event => {
        const dropdown = $('[data-cy="cart-dropdown"]');
        if (header.dropdownOpen && dropdown && !dropdown.contains(event.target)) {
          header.closeDropdown();
        }
      });
      header.renderCurrency();
      header.renderCart();
      ApiGateway.getSupportedCurrencyList()
        .then((codes = []) => {
          state.currencyCodes = codes.sort();
          header.renderCurrency();
        })
        .catch(logError);
    },

    closeDropdown() {
      header.dropdownOpen = false;
      header.renderCart();
    },

    renderCurrency() {
      const select = $('.currency-select');
      select.replaceChildren(...state.currencyCodes.map(code => h('option', { value: code }, code)));
      select.value = state.currency;
      $('.selected-currency').textContent = getSymbolFromCurrency(state.currency) || '';
    },

    renderCart() {
      const { items } = state.cart;
      const icon = $('.cart-icon');
      const existing = icon.querySelector('[data-cy="cart-item-count"]');
      if (existing) existing.remove();
      if (items.length) {
        icon.appendChild(h('span', { class: 'cart-icon-count', dataCy: 'cart-item-count' }, items.length));
      }

      const root = $('#cart-dropdown-root');
      if (!header.dropdownOpen) {
        root.replaceChildren();
        return;
      }
      root.replaceChildren(
        h(
          'div',
          { class: 'cart-dropdown', dataCy: 'cart-dropdown' },
          h(
            'div',
            { class: 'cart-dropdown-content' },
            h(
              'div',
              { class: 'cart-dropdown-header' },
              h('h5', { class: 'cart-dropdown-title' }, 'Shopping Cart'),
              h('span', { onClick: () => header.closeDropdown() }, 'Close')
            ),
            h(
              'div',
              { class: 'cart-dropdown-item-list' },
              !items.length && h('h3', { class: 'cart-dropdown-empty' }, 'Your shopping cart is empty'),
              items.map(({ quantity, product: { name, picture, id, priceUsd = defaultMoney() } }) =>
                h(
                  'div',
                  { class: 'cart-dropdown-item', dataCy: 'cart-dropdown-item', 'data-product-id': id },
                  picture &&
                    h('img', { class: 'cart-dropdown-item-image', src: '/images/products/' + picture, alt: name, width: 80, height: 80 }),
                  h(
                    'div',
                    { class: 'cart-dropdown-item-details' + (picture ? '' : ' cart-dropdown-item-details--full') },
                    h('p', { class: 'cart-dropdown-item-name' }, name),
                    productPrice(priceUsd),
                    h('p', { class: 'cart-dropdown-item-name cart-dropdown-item-quantity' }, `Quantity: ${quantity}`)
                  )
                )
              )
            )
          ),
          h('a', { href: '/cart' }, h('button', { class: 'btn', dataCy: 'cart-go-to-shopping' }, 'Go to Shopping Cart'))
        )
      );
    },
  };

  function renderFooter() {
    const el = $('[data-cy="session-id"]');
    if (el) el.textContent = `session-id: ${userId}`;
  }

  // ---------------------------------------------------------------------------------------------
  // Cart state (providers/Cart.provider.tsx)
  // ---------------------------------------------------------------------------------------------

  async function loadCart() {
    try {
      state.cart = (await ApiGateway.getCart(state.currency)) || { userId: '', items: [] };
    } catch (error) {
      logError(error);
    }
    header.renderCart();
    pageHooks.onCartChange();
  }

  async function addItem(item) {
    await ApiGateway.addCartItem({ ...item, currencyCode: state.currency });
  }

  async function updateItemQuantity(productId, newQuantity) {
    const existing = state.cart.items.find(i => i.productId === productId);
    const delta = newQuantity - ((existing && existing.quantity) || 0);
    if (delta !== 0) {
      try {
        await ApiGateway.addCartItem({ productId, quantity: delta, currencyCode: state.currency });
      } catch (error) {
        logError(error);
      }
      await loadCart();
    }
  }

  async function emptyCart() {
    try {
      await ApiGateway.emptyCart();
    } catch (error) {
      logError(error);
    }
    await loadCart();
  }

  // ---------------------------------------------------------------------------------------------
  // Pages
  // ---------------------------------------------------------------------------------------------

  /** pages/index.tsx */
  function homePage() {
    const list = $('#product-list');
    async function load() {
      try {
        const productList = (await ApiGateway.listProducts(state.currency)) || [];
        list.replaceChildren(...productList.map(productCard));
      } catch (error) {
        logError(error);
      }
    }
    pageHooks.onCurrencyChange = load;
    load();
  }

  /** pages/product/[productId]/index.tsx */
  function productPage() {
    const productId = decodeURIComponent(window.location.pathname.split('/')[2] || '');
    const root = $('#product-detail');
    let quantity = 1;
    let product = {};
    let recommended = [];

    function render() {
      const { name, picture, description, priceUsd = defaultMoney() } = product;
      root.replaceChildren(
        h(
          'div',
          { class: 'product-detail-container' },
          picture &&
            h('div', {
              class: 'product-detail-image',
              dataCy: 'product-picture',
              style: `background-image: url("/images/products/${picture}")`,
            }),
          h(
            'div',
            { class: 'product-detail-details' + (picture ? '' : ' product-detail-details--full') },
            h('h5', { class: 'product-detail-name', dataCy: 'product-name' }, name),
            h('p', { class: 'product-detail-text product-detail-description', dataCy: 'product-description' }, description),
            h('p', { class: 'product-detail-text product-detail-price' }, productPrice(priceUsd)),
            h('p', { class: 'product-detail-text' }, 'Quantity'),
            selectBox(
              {
                dataCy: 'product-quantity',
                value: quantity,
                onChange: event => {
                  quantity = +event.target.value;
                },
              },
              quantityOptions
            ),
            h(
              'button',
              { class: 'btn add-to-cart', dataCy: 'product-add-to-cart', onClick: onAddItem },
              h('img', { src: '/icons/Cart.svg', height: 15, width: 15, alt: 'cart' }),
              ' Add To Cart'
            )
          )
        ),
        recommendations(recommended)
      );
    }

    async function onAddItem() {
      await addItem({ productId, quantity });
      window.location.assign('/cart');
    }

    async function load() {
      // the recommendations are keyed by the product plus everything in the cart
      const cartLoaded = cartReady;
      const productPromise = ApiGateway.getProduct(productId, state.currency).catch(logError);
      const [loaded] = await Promise.all([productPromise, cartLoaded]);
      if (loaded) {
        product = loaded;
      }
      render();

      const data = await loadAdData({
        productIds: [productId, ...state.cart.items.map(({ productId: id }) => id)],
        contextKeys: [...new Set(product.categories)],
      });
      renderAd(data.adList);
      recommended = data.recommendedProductList;
      render();
    }

    pageHooks.onCurrencyChange = load;
    render();
    load();
  }

  /** pages/cart/index.tsx with components/Cart/*, CartItems, CheckoutForm */
  function cartPage() {
    const root = $('#cart');
    const recommendationsHost = h('div', {});
    let mode = null; // 'detail' | 'empty'
    let itemsHost = null;
    let shippingCost = defaultMoney();
    let shippingRequest = 0;
    let recommended = [];
    let adKey = '';

    const address = {
      streetAddress: '1600 Amphitheatre Parkway',
      city: 'Mountain View',
      state: 'CA',
      country: 'United States',
      zipCode: '94043',
    };

    function renderRecommendations() {
      recommendationsHost.replaceChildren(...[recommendations(recommended)].filter(Boolean));
    }

    function emptyView() {
      return h(
        'div',
        { class: 'cart-empty-container' },
        h('h1', { class: 'cart-empty-title' }, 'Your shopping cart is empty!'),
        h('h3', { class: 'cart-empty-subtitle' }, 'Items you add to your shopping cart will appear here.'),
        h(
          'div',
          { class: 'cart-button-container' },
          h('a', { href: '/' }, h('button', { class: 'btn', type: 'submit' }, 'Continue Shopping'))
        )
      );
    }

    function cartItemRow({ productId, product, quantity }) {
      const { id, name, picture, priceUsd = defaultMoney() } = product;
      const totalNanos = Number(priceUsd.nanos) * quantity;
      const linePrice = {
        units: Number(priceUsd.units) * quantity + Math.floor(totalNanos / 1_000_000_000),
        nanos: totalNanos % 1_000_000_000,
        currencyCode: priceUsd.currencyCode,
      };
      return h(
        'div',
        { class: 'cart-item', 'data-product-id': productId },
        h(
          'a',
          { href: `/product/${id}` },
          h(
            'div',
            { class: 'cart-item-name-container' },
            h('img', { class: 'cart-item-image', alt: name, src: picture ? '/images/products/' + picture : undefined }),
            h('p', {}, name)
          )
        ),
        h(
          'div',
          { class: 'cart-item-details' },
          selectBox(
            { value: quantity, onChange: event => updateItemQuantity(id, +event.target.value) },
            quantityOptions
          )
        ),
        h('div', { class: 'cart-item-details' }, h('div', { class: 'cart-item-price-container' }, h('p', {}, productPrice(priceUsd)))),
        h('div', { class: 'cart-item-details' }, h('div', { class: 'cart-item-price-container' }, h('p', {}, productPrice(linePrice))))
      );
    }

    function renderItems() {
      if (!itemsHost) return;
      const productList = state.cart.items;
      const shipping = shippingCost;

      const nanoSum =
        (productList.reduce((acc, { product: { priceUsd: { nanos = 0 } = {} }, quantity }) => acc + Number(nanos) * quantity, 0) +
          shipping.nanos) ||
        0;
      const nanoExceed = Math.floor(nanoSum / 1000000000);
      const unitSum =
        productList.reduce((acc, { product: { priceUsd: { units = 0 } = {} }, quantity }) => acc + Number(units) * quantity, 0) +
        (shipping.units || 0) +
        nanoExceed;
      const total = { units: unitSum, currencyCode: state.currency, nanos: nanoSum % 1000000000 };

      itemsHost.replaceChildren(
        h(
          'section',
          { class: 'cart-items' },
          h(
            'div',
            { class: 'cart-items-header' },
            h('label', {}, 'Product'),
            h('label', {}, 'Quantity'),
            h('label', {}, 'Price'),
            h('label', {}, 'Total')
          ),
          productList.map(cartItemRow),
          h('div', { class: 'cart-items-data-row' }, h('span', {}, 'Shipping'), productPrice(shipping)),
          h(
            'div',
            { class: 'cart-items-data-row' },
            h('h3', { class: 'cart-items-total-text' }, 'Total'),
            h('h3', { class: 'cart-items-total-text' }, productPrice(total))
          )
        )
      );
    }

    async function loadShipping() {
      const requestId = ++shippingRequest;
      try {
        const cost = await ApiGateway.getShippingCost(state.cart.items, state.currency, address);
        if (requestId === shippingRequest && cost) {
          shippingCost = cost;
          renderItems();
        }
      } catch (error) {
        logError(error);
      }
    }

    function checkoutForm() {
      const currentYear = new Date().getFullYear();
      const yearList = Array.from(new Array(20), (v, i) => i + currentYear);
      const months = [
        'January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November',
        'January', // sic: the original form labels month 12 "January" as well
      ];

      const textInput = (label, { type = 'text', id, name, value, placeholder, pattern }) =>
        h(
          'div',
          { class: 'input-row' },
          h('p', { class: 'input-label' }, label),
          h('input', { class: 'input', id, name, type, value, placeholder, pattern, required: true })
        );
      const selectInput = (label, { id, name, value }, options) => {
        const select = h('select', { class: 'input-select', id, name }, options);
        select.value = String(value);
        return h(
          'div',
          { class: 'input-row' },
          h('p', { class: 'input-label' }, label),
          select,
          h('img', { class: 'input-arrow', src: '/icons/Chevron.svg', alt: 'arrow' })
        );
      };

      const form = h(
        'form',
        { class: 'checkout-form' },
        h('h1', { class: 'checkout-form-title' }, 'Shipping Address'),
        textInput('E-mail Address', { type: 'email', id: 'email', name: 'email', value: 'someone@example.com' }),
        textInput('Street Address', { id: 'street_address', name: 'streetAddress', value: '1600 Amphitheatre Parkway' }),
        textInput('Zip Code', { id: 'zip_code', name: 'zipCode', value: '94043' }),
        textInput('City', { id: 'city', name: 'city', value: 'Mountain View' }),
        h(
          'div',
          { class: 'checkout-form-state-row' },
          textInput('State', { id: 'state', name: 'state', value: 'CA' }),
          textInput('Country', { id: 'country', name: 'country', value: 'United States', placeholder: 'Country Name' })
        ),
        h('div', {}, h('h1', { class: 'checkout-form-title' }, 'Payment Method')),
        textInput('Credit Card Number', {
          id: 'credit_card_number',
          name: 'creditCardNumber',
          value: '4432-8015-6152-0454',
          placeholder: '0000-0000-0000-0000',
          pattern: '\\d{4}-\\d{4}-\\d{4}-\\d{4}',
        }),
        h(
          'div',
          { class: 'checkout-form-card-row' },
          selectInput(
            'Month',
            { id: 'credit_card_expiration_month', name: 'creditCardExpirationMonth', value: 1 },
            months.map((month, i) => h('option', { value: String(i + 1) }, month))
          ),
          selectInput(
            'Year',
            { id: 'credit_card_expiration_year', name: 'creditCardExpirationYear', value: 2030 },
            yearList.map(year => h('option', { value: String(year) }, year))
          ),
          textInput('CVV', { type: 'password', id: 'credit_card_cvv', name: 'creditCardCvv', value: '672', pattern: '\\d{3}' })
        ),
        h(
          'div',
          { class: 'checkout-form-submit' },
          h('a', { href: '/' }, h('button', { class: 'btn btn--secondary checkout-form-button', type: 'button' }, 'Continue Shopping')),
          h('button', { class: 'btn checkout-form-button', dataCy: 'checkout-place-order', type: 'submit' }, 'Place Order')
        )
      );

      form.addEventListener('submit', async event => {
        event.preventDefault();
        const data = Object.fromEntries(new FormData(form).entries());
        try {
          const order = await ApiGateway.placeOrder({
            currencyCode: state.currency,
            userId,
            email: data.email,
            address: {
              streetAddress: data.streetAddress,
              state: data.state,
              country: data.country,
              city: data.city,
              zipCode: data.zipCode,
            },
            userCurrency: state.currency,
            creditCard: {
              creditCardCvv: data.creditCardCvv,
              creditCardExpirationMonth: data.creditCardExpirationMonth,
              creditCardExpirationYear: data.creditCardExpirationYear,
              creditCardNumber: data.creditCardNumber,
            },
          });
          const query = new URLSearchParams({ order: JSON.stringify(order) });
          window.location.assign(`/cart/checkout/${order.orderId}?${query.toString()}`);
        } catch (error) {
          // The original page leaves the rejection of the placeOrder mutation unhandled
          logError(error);
        }
      });
      return form;
    }

    function detailView() {
      itemsHost = h('div', {});
      return h(
        'div',
        { class: 'cart-container' },
        h(
          'div',
          {},
          h(
            'div',
            { class: 'cart-header' },
            h('h1', { class: 'cart-title' }, 'Shopping Cart'),
            h('button', { class: 'btn btn--link empty-cart-button', onClick: () => emptyCart() }, 'Empty Cart')
          ),
          itemsHost
        ),
        checkoutForm()
      );
    }

    async function refreshAdData() {
      const items = state.cart.items;
      const productIds = items.map(({ productId }) => productId);
      const contextKeys = [...new Set(items.flatMap(({ product }) => product.categories))];
      const key = JSON.stringify([productIds, contextKeys, state.currency]);
      if (key === adKey) return;
      adKey = key;
      const data = await loadAdData({ productIds, contextKeys });
      if (key !== adKey) return;
      recommended = data.recommendedProductList;
      renderRecommendations();
    }

    function onCartChange() {
      const hasItems = state.cart.items.length > 0;
      const nextMode = hasItems ? 'detail' : 'empty';
      if (nextMode !== mode) {
        mode = nextMode;
        root.replaceChildren(hasItems ? detailView() : emptyView(), recommendationsHost);
      }
      if (hasItems) {
        renderItems();
        loadShipping();
      }
      refreshAdData();
    }

    pageHooks.onCartChange = onCartChange;
    pageHooks.onCurrencyChange = () => {
      adKey = '';
    };
    // first render happens when the cart arrives (loadCart -> onCartChange)
    cartReady.then(() => {
      if (mode === null) onCartChange();
    });
  }

  /** pages/cart/checkout/[orderId]/index.tsx */
  function checkoutPage() {
    const root = $('#checkout');
    let order = {};
    try {
      order = JSON.parse(new URLSearchParams(window.location.search).get('order') || '{}');
    } catch (error) {
      logError(error);
    }
    const { orderId, items = [], shippingAddress = {}, shippingCost = { units: 0, currencyCode: 'USD', nanos: 0 } } = order;

    const itemsTotal = items.reduce(
      (acc, { item, cost = { units: 0, nanos: 0, currencyCode: 'USD' } }) => ({
        units: acc.units + (cost.units || 0) * item.quantity,
        nanos: acc.nanos + (cost.nanos || 0) * item.quantity,
        currencyCode: cost.currencyCode || 'USD',
      }),
      { units: 0, nanos: 0, currencyCode: 'USD' }
    );
    const totalNanos = itemsTotal.nanos + (shippingCost.nanos || 0);
    const nanoExceed = Math.floor(totalNanos / 1000000000);
    const orderTotal = {
      units: itemsTotal.units + (shippingCost.units || 0) + nanoExceed,
      nanos: totalNanos % 1000000000,
      currencyCode: shippingCost.currencyCode || 'USD',
    };

    const recommendationsHost = h('div', {});

    function orderItem({ item, cost = { units: 0, currencyCode: 'USD', nanos: 0 } }) {
      const itemTotal = {
        units: (cost.units || 0) * item.quantity,
        nanos: (cost.nanos || 0) * item.quantity,
        currencyCode: cost.currencyCode || 'USD',
      };
      // Handle nanos overflow
      itemTotal.units += Math.floor(itemTotal.nanos / 1000000000);
      itemTotal.nanos = itemTotal.nanos % 1000000000;

      return h(
        'div',
        { class: 'checkout-order-item', dataCy: 'checkout-item' },
        h('img', { class: 'checkout-item-image', src: '/images/products/' + item.product.picture, alt: item.product.name }),
        h(
          'div',
          { class: 'checkout-item-details' },
          h('h5', { class: 'checkout-item-name' }, item.product.name),
          h('p', { class: 'checkout-item-quantity' }, `Quantity: ${item.quantity}`)
        ),
        h('div', { class: 'checkout-item-price' }, productPrice(itemTotal))
      );
    }

    root.replaceChildren(
      h(
        'div',
        { class: 'checkout-container' },
        h(
          'div',
          { class: 'checkout-left' },
          h('h1', { class: 'checkout-title' }, 'Your order is complete!'),
          h('h3', { class: 'checkout-subtitle' }, "We've sent you a confirmation email."),
          h(
            'div',
            { class: 'checkout-order-info' },
            h('span', { class: 'checkout-info-label' }, 'Order ID:'),
            h('span', { class: 'checkout-info-value' }, orderId)
          )
        ),
        h(
          'div',
          { class: 'checkout-right' },
          h('h4', { class: 'checkout-section-title' }, 'Shipping Address'),
          h('p', { class: 'checkout-address-text' }, shippingAddress.streetAddress),
          h('p', { class: 'checkout-address-text' }, `${shippingAddress.city}, ${shippingAddress.state} ${shippingAddress.zipCode}`),
          h('p', { class: 'checkout-address-text' }, shippingAddress.country)
        ),
        h(
          'div',
          { class: 'checkout-items-section' },
          h('h4', { class: 'checkout-section-title' }, 'Order Items'),
          h('div', { class: 'checkout-item-list' }, items.map(orderItem)),
          h(
            'div',
            { class: 'checkout-order-summary' },
            h('div', { class: 'checkout-summary-row' }, h('span', {}, 'Shipping:'), productPrice(shippingCost)),
            h(
              'div',
              { class: 'checkout-total-row' },
              h('span', { class: 'checkout-total-label' }, 'Total:'),
              h('span', { class: 'checkout-total-amount' }, productPrice(orderTotal))
            )
          )
        ),
        h(
          'div',
          { class: 'checkout-button-container' },
          h('a', { href: '/' }, h('button', { class: 'btn', type: 'submit' }, 'Continue Shopping'))
        )
      ),
      recommendationsHost
    );

    async function load() {
      const data = await loadAdData({
        productIds: items.map(({ item }) => (item && item.productId) || ''),
        contextKeys: [...new Set(items.flatMap(({ item }) => item.product.categories || []))],
      });
      renderAd(data.adList);
      recommendationsHost.replaceChildren(...[recommendations(data.recommendedProductList)].filter(Boolean));
    }

    pageHooks.onCurrencyChange = load;
    load();
  }

  // ---------------------------------------------------------------------------------------------
  // Bootstrap
  // ---------------------------------------------------------------------------------------------

  let cartReady = Promise.resolve();

  function start() {
    renderFooter();
    header.init();
    const page = document.body.getAttribute('data-page');
    const pages = { home: homePage, product: productPage, cart: cartPage, checkout: checkoutPage };
    // The cart is needed by every page (cart icon, product recommendations, the cart page itself)
    cartReady = loadCart();
    if (pages[page]) {
      pages[page]();
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
