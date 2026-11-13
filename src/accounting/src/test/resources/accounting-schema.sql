-- Same tables as src/postgresql/init.sql (accounting schema). TEXT columns are VARCHAR here because
-- H2 maps TEXT to a CLOB, which cannot be a primary key.
CREATE SCHEMA IF NOT EXISTS accounting;

CREATE TABLE IF NOT EXISTS accounting."order" (
    order_id VARCHAR(255) PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS accounting.shipping (
    shipping_tracking_id VARCHAR(255) PRIMARY KEY,
    shipping_cost_currency_code VARCHAR(255) NOT NULL,
    shipping_cost_units BIGINT NOT NULL,
    shipping_cost_nanos INT NOT NULL,
    street_address VARCHAR(255),
    city VARCHAR(255),
    state VARCHAR(255),
    country VARCHAR(255),
    zip_code VARCHAR(255),
    order_id VARCHAR(255) NOT NULL,
    FOREIGN KEY (order_id) REFERENCES accounting."order"(order_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS accounting.orderitem (
    item_cost_currency_code VARCHAR(255) NOT NULL,
    item_cost_units BIGINT NOT NULL,
    item_cost_nanos INT NOT NULL,
    product_id VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    order_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (order_id, product_id),
    FOREIGN KEY (order_id) REFERENCES accounting."order"(order_id) ON DELETE CASCADE
);
