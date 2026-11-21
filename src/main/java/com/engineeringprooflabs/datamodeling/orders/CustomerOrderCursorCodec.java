package com.engineeringprooflabs.datamodeling.orders;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class CustomerOrderCursorCodec {

    private static final int VERSION = 1;
    private static final int MAX_CURSOR_BYTES = 512;

    public String encode(String backend, String customerId, int limit, OrderSummary lastItem) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(VERSION);
                output.writeUTF(backend);
                output.writeUTF(customerId);
                output.writeInt(limit);
                output.writeLong(lastItem.createdAt().getEpochSecond());
                output.writeInt(lastItem.createdAt().getNano());
                UUID id = UUID.fromString(lastItem.id());
                output.writeLong(id.getMostSignificantBits());
                output.writeLong(id.getLeastSignificantBits());
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException impossibleForMemoryStream) {
            throw new IllegalStateException("Could not encode an order cursor.", impossibleForMemoryStream);
        }
    }

    public CustomerOrderCursor decode(String encoded) {
        try {
            byte[] payload = Base64.getUrlDecoder().decode(encoded);
            if (payload.length > MAX_CURSOR_BYTES) {
                throw new InvalidCursorException();
            }
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
                int version = input.readUnsignedByte();
                String backend = input.readUTF();
                String customerId = input.readUTF();
                int limit = input.readInt();
                long epochSecond = input.readLong();
                int nano = input.readInt();
                long mostSignificantBits = input.readLong();
                long leastSignificantBits = input.readLong();

                if (version != VERSION || input.available() != 0 || limit < 1 || limit > 50
                        || nano < 0 || nano >= 1_000_000_000) {
                    throw new InvalidCursorException();
                }
                Instant createdAt = Instant.ofEpochSecond(epochSecond, nano);
                UUID id = new UUID(mostSignificantBits, leastSignificantBits);
                return new CustomerOrderCursor(backend, customerId, limit, createdAt, id);
            }
        } catch (IOException | IllegalArgumentException | DateTimeException | ArithmeticException invalidEncoding) {
            throw new InvalidCursorException();
        }
    }
}
