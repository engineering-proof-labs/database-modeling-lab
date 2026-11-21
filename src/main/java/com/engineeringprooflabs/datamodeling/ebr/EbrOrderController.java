package com.engineeringprooflabs.datamodeling.ebr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("oracle-ebr")
@RequestMapping("/ebr/orders")
public class EbrOrderController {

    private final JdbcTemplate jdbc;
    private final Version version;

    public EbrOrderController(JdbcTemplate jdbc, @Value("${lab.ebr.version}") Version version) {
        this.jdbc = jdbc;
        this.version = version;
    }

    @GetMapping("/{id}")
    public Map<String, Object> find(@PathVariable long id) {
        validateId(id);
        return jdbc.query("SELECT id, total_value FROM purchase_order_api WHERE id = ?", rows -> {
            if (!rows.next()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
            }
            return Map.of("id", rows.getLong(1), version.field, rows.getBigDecimal(2));
        }, id);
    }

    @PutMapping("/{id}")
    @Transactional
    public Map<String, Object> put(@PathVariable long id, @RequestBody Total request) {
        validateId(id);
        BigDecimal value = version.validate(request);
        jdbc.update("""
                MERGE INTO purchase_order_api target
                USING (SELECT ? AS id, ? AS total_value FROM dual) incoming
                ON (target.id = incoming.id)
                WHEN MATCHED THEN UPDATE SET target.total_value = incoming.total_value
                WHEN NOT MATCHED THEN INSERT (id, total_value) VALUES (incoming.id, incoming.total_value)
                """, id, value);
        return Map.of("id", id, version.field, value);
    }

    private static void validateId(long id) {
        if (id < 1 || id > 999_999_999_999L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order ID is out of range");
        }
    }

    public record Total(BigDecimal totalCents, BigDecimal totalAmount) {
    }

    enum Version {
        V1("totalCents", 0),
        V2("totalAmount", 2);

        private final String field;
        private final int scale;

        Version(String field, int scale) {
            this.field = field;
            this.scale = scale;
        }

        BigDecimal validate(Total request) {
            BigDecimal expected = this == V1 ? request.totalCents() : request.totalAmount();
            BigDecimal unexpected = this == V1 ? request.totalAmount() : request.totalCents();
            if (expected == null || unexpected != null || expected.signum() < 0
                    || expected.compareTo(BigDecimal.valueOf(9_999_999_999L, scale)) > 0) {
                throw invalidTotal();
            }
            try {
                BigDecimal normalized = expected.stripTrailingZeros();
                if (normalized.scale() > scale) {
                    throw invalidTotal();
                }
                return normalized.setScale(scale, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException cannotRepresentExactly) {
                throw invalidTotal();
            }
        }

        private ResponseStatusException invalidTotal() {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + field);
        }
    }
}
