package com.engineeringprooflabs.datamodeling.orders;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

final class PendingOrderCursorCodec {

    private static final String VERSION = "p1";
    private static final Instant MIN_TIME = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999Z");

    private PendingOrderCursorCodec() {
    }

    static String encode(String backend, int limit, Instant asOf, OrderSummary last) {
        String payload = String.join("|", VERSION, backend, Integer.toString(limit),
                Long.toString(asOf.toEpochMilli()), last.createdAt().toString(), last.id());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
    }

    static PendingOrderWindow decode(String encoded, String backend, int limit) {
        if (encoded.length() > 256) {
            throw new InvalidCursorException();
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII);
            String[] fields = payload.split("\\|", -1);
            if (fields.length != 6 || !VERSION.equals(fields[0]) || !backend.equals(fields[1])
                    || limit != Integer.parseInt(fields[2])) {
                throw new InvalidCursorException();
            }
            Instant asOf = Instant.ofEpochMilli(Long.parseLong(fields[3]));
            Instant createdAt = Instant.parse(fields[4]);
            UUID id = UUID.fromString(fields[5]);
            var window = new PendingOrderWindow(asOf, new PendingOrderWindow.Position(createdAt, id));
            if (!id.toString().equals(fields[5]) || window.start().isBefore(MIN_TIME) || asOf.isAfter(MAX_TIME)
                    || createdAt.isBefore(window.start()) || !createdAt.isBefore(asOf)) {
                throw new InvalidCursorException();
            }
            return window;
        } catch (IllegalArgumentException | DateTimeException invalidEncoding) {
            throw new InvalidCursorException();
        }
    }
}
