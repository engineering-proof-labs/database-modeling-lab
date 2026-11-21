CREATE INDEX idx_orders_pending_created_id ON orders (created_at DESC, id DESC)
    WHERE status = 'PENDING';
