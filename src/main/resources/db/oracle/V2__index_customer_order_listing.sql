CREATE INDEX idx_orders_customer_created_id ON orders (customer_id, created_at, id);
