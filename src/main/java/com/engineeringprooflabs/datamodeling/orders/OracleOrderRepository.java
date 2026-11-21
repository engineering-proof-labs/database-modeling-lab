package com.engineeringprooflabs.datamodeling.orders;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("oracle")
public class OracleOrderRepository implements OrderStore, CreateFailureProbe {

    private static final RowMapper<OrderHeader> ORDER_HEADER_MAPPER = (rs, rowNum) -> {
        LocalDateTime confirmedAt = rs.getObject("confirmed_at", LocalDateTime.class);
        return new OrderHeader(
                rs.getString("id"),
                rs.getString("customer_id"),
                rs.getString("status"),
                rs.getLong("total_cents"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                confirmedAt == null ? null : confirmedAt.toInstant(ZoneOffset.UTC));
    };

    private static final RowMapper<OrderSummary> ORDER_SUMMARY_MAPPER = (rs, rowNum) -> new OrderSummary(
            rs.getString("id"),
            rs.getString("customer_id"),
            rs.getString("status"),
            rs.getLong("total_cents"),
            rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    private final JdbcTemplate jdbc;

    public OracleOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String backend() {
        return "oracle";
    }

    @Override
    public boolean customerExists(String customerId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customers WHERE id = ?", Integer.class, customerId);
        return Integer.valueOf(1).equals(count);
    }

    @Override
    @Transactional
    public void createOrder(OrderView order) {
        insertOrder(order);
        insertItems(order);
    }

    @Override
    @Transactional
    public void insertHeaderThenFail(OrderView order) {
        insertOrder(order);
        throw new InjectedWriteFailureException();
    }

    private void insertOrder(OrderView order) {
        jdbc.update("""
                INSERT INTO orders (id, customer_id, status, total_cents, created_at, confirmed_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                order.id(), order.customerId(), order.status(), order.totalCents(),
                LocalDateTime.ofInstant(order.createdAt(), ZoneOffset.UTC), null);
    }

    private void insertItems(OrderView order) {
        jdbc.batchUpdate("""
                INSERT INTO order_items (order_id, item_number, product_name, quantity, unit_price_cents, line_total_cents)
                VALUES (?, ?, ?, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int index) throws SQLException {
                OrderItemView item = order.items().get(index);
                ps.setString(1, order.id());
                ps.setInt(2, item.itemNumber());
                ps.setString(3, item.productName());
                ps.setInt(4, item.quantity());
                ps.setLong(5, item.unitPriceCents());
                ps.setLong(6, item.lineTotalCents());
            }

            @Override
            public int getBatchSize() {
                return order.items().size();
            }
        });
    }

    @Override
    public int confirmPending(String id, Instant confirmedAt) {
        String orderId = parseOrderId(id);
        if (orderId == null) {
            return 0;
        }
        return jdbc.update("""
                UPDATE orders
                SET status = 'CONFIRMED', confirmed_at = ?
                WHERE id = ? AND status = 'PENDING'
                """,
                LocalDateTime.ofInstant(confirmedAt, ZoneOffset.UTC), orderId);
    }

    @Override
    public boolean orderExists(String id) {
        String orderId = parseOrderId(id);
        if (orderId == null) {
            return false;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE id = ?", Integer.class, orderId);
        return Integer.valueOf(1).equals(count);
    }

    @Override
    public List<OrderSummary> listCustomerOrders(String customerId, CustomerOrderCursor cursor, int fetchLimit) {
        if (cursor == null) {
            return jdbc.query("""
                    SELECT id, customer_id, status, total_cents, created_at
                    FROM orders
                    WHERE customer_id = ?
                    ORDER BY created_at DESC, id DESC
                    FETCH FIRST ? ROWS ONLY
                    """, ORDER_SUMMARY_MAPPER, customerId, fetchLimit);
        }

        LocalDateTime cursorTime = LocalDateTime.ofInstant(cursor.createdAt(), ZoneOffset.UTC);
        if (cursorTime.getYear() < 1 || cursorTime.getYear() > 9999) {
            throw new InvalidCursorException();
        }
        return jdbc.query("""
                SELECT id, customer_id, status, total_cents, created_at
                FROM orders
                WHERE customer_id = ?
                  AND (created_at < ? OR (created_at = ? AND id < ?))
                ORDER BY created_at DESC, id DESC
                FETCH FIRST ? ROWS ONLY
                """, ORDER_SUMMARY_MAPPER, customerId, cursorTime, cursorTime, cursor.id().toString(), fetchLimit);
    }

    @Override
    public Optional<OrderView> findById(String id) {
        String orderId = parseOrderId(id);
        if (orderId == null) {
            return Optional.empty();
        }

        List<OrderHeader> headers = jdbc.query("""
                SELECT id, customer_id, status, total_cents, created_at, confirmed_at
                FROM orders WHERE id = ?
                """, ORDER_HEADER_MAPPER, orderId);
        if (headers.isEmpty()) {
            return Optional.empty();
        }

        OrderHeader header = headers.getFirst();
        List<OrderItemView> items = jdbc.query("""
                SELECT item_number, product_name, quantity, unit_price_cents, line_total_cents
                FROM order_items WHERE order_id = ? ORDER BY item_number
                """, (rs, rowNum) -> new OrderItemView(
                rs.getInt("item_number"), rs.getString("product_name"), rs.getInt("quantity"),
                rs.getLong("unit_price_cents"), rs.getLong("line_total_cents")), orderId);

        return Optional.of(new OrderView(header.id(), header.customerId(), header.status(),
                header.totalCents(), header.createdAt(), header.confirmedAt(), items));
    }

    private record OrderHeader(
            String id, String customerId, String status, long totalCents,
            Instant createdAt, Instant confirmedAt) {
    }

    private String parseOrderId(String id) {
        try {
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException invalidId) {
            return null;
        }
    }
}
