package com.engineeringprooflabs.datamodeling.orders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;

@Repository
@Profile("dynamodb")
public class DynamoOrderRepository implements OrderStore {

    private static final String TABLE = "orders-lab";
    private static final String CUSTOMER_INDEX = "customer-created";
    private static final String ID = "id";
    private static final String CUSTOMER = "customerId";
    private static final String SORT = "createdOrder";

    private final DynamoDbClient dynamo;

    public DynamoOrderRepository(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    @PostConstruct
    void initializeLocalTable() {
        try {
            dynamo.createTable(request -> request.tableName(TABLE)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            a -> a.attributeName(ID).attributeType("S"),
                            a -> a.attributeName(CUSTOMER).attributeType("S"),
                            a -> a.attributeName(SORT).attributeType("S"))
                    .keySchema(k -> k.attributeName(ID).keyType(KeyType.HASH))
                    .globalSecondaryIndexes(index -> index.indexName(CUSTOMER_INDEX)
                            .keySchema(k -> k.attributeName(CUSTOMER).keyType(KeyType.HASH),
                                    k -> k.attributeName(SORT).keyType(KeyType.RANGE))
                            .projection(p -> p.projectionType(ProjectionType.INCLUDE)
                                    .nonKeyAttributes("status", "createdAt", "totalCents"))));
        } catch (ResourceInUseException existingTable) {
            // Retain local data across API restarts.
        }

        for (String customer : List.of("customer-001", "customer-002", "customer-003")) {
            seedIfAbsent(Map.of(ID, str(customerKey(customer))));
        }
        for (int i = 1; i <= 4; i++) {
            String id = "30000000-0000-4000-8000-00000000000" + i;
            long price = 1000L * i;
            boolean pending = i <= 2;
            Instant createdAt = Instant.parse(pending ? "2026-11-20T12:00:00Z" : "2026-11-19T12:00:00Z");
            Instant confirmedAt = pending ? null : createdAt.plusSeconds(60L * (i + 2));
            seedIfAbsent(toItem(new OrderView(id, "customer-003", pending ? "PENDING" : "CONFIRMED",
                    price, createdAt, confirmedAt,
                    List.of(new OrderItemView(1, "Fixture item " + i, 1, price, price)))));
        }
    }

    @Override
    public String backend() {
        return "dynamodb";
    }

    @Override
    public boolean customerExists(String customerId) {
        return customerId.length() <= 64 && keyExists(customerKey(customerId));
    }

    @Override
    public void createOrder(OrderView order) {
        putIfAbsent(toItem(order));
    }

    @Override
    public Optional<OrderView> findById(String id) {
        if (!isOrderId(id)) {
            return Optional.empty();
        }
        var response = dynamo.getItem(request -> request.tableName(TABLE).key(key(id)).consistentRead(true));
        return response.hasItem() ? Optional.of(toOrder(response.item())) : Optional.empty();
    }

    @Override
    public int confirmPending(String id, Instant confirmedAt) {
        if (!isOrderId(id)) {
            return 0;
        }
        try {
            dynamo.updateItem(request -> request.tableName(TABLE).key(key(id))
                    .conditionExpression("#status = :pending")
                    .updateExpression("SET #status = :confirmed, confirmedAt = :time")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":pending", str("PENDING"),
                            ":confirmed", str("CONFIRMED"),
                            ":time", num(confirmedAt.toEpochMilli()))));
            return 1;
        } catch (ConditionalCheckFailedException notPending) {
            return 0;
        }
    }

    @Override
    public boolean orderExists(String id) {
        return isOrderId(id) && keyExists(id);
    }

    @Override
    public List<OrderSummary> listCustomerOrders(String customerId, CustomerOrderCursor cursor, int fetchLimit) {
        Map<String, AttributeValue> startKey = null;
        if (cursor != null) {
            String cursorId = cursor.id().toString();
            try {
                startKey = Map.of(ID, str(cursorId), CUSTOMER, str(customerId),
                        SORT, str(sortKey(cursor.createdAt(), cursorId)));
            } catch (ArithmeticException outOfRange) {
                throw new InvalidCursorException();
            }
        }

        var query = QueryRequest.builder().tableName(TABLE).indexName(CUSTOMER_INDEX)
                .keyConditionExpression("customerId = :customer")
                .expressionAttributeValues(Map.of(":customer", str(customerId)))
                .scanIndexForward(false);

        List<OrderSummary> orders = new ArrayList<>(fetchLimit);
        do {
            var page = dynamo.query(query.exclusiveStartKey(startKey).limit(fetchLimit - orders.size()).build());
            page.items().stream().map(DynamoOrderRepository::toSummary).forEach(orders::add);
            startKey = page.lastEvaluatedKey();
        } while (orders.size() < fetchLimit && !startKey.isEmpty());
        return orders;
    }

    private boolean keyExists(String id) {
        return dynamo.getItem(request -> request.tableName(TABLE).key(key(id)).consistentRead(true)
                .projectionExpression("#id").expressionAttributeNames(Map.of("#id", ID))).hasItem();
    }

    private void putIfAbsent(Map<String, AttributeValue> item) {
        dynamo.putItem(request -> request.tableName(TABLE).item(item)
                .conditionExpression("attribute_not_exists(#id)").expressionAttributeNames(Map.of("#id", ID)));
    }

    private void seedIfAbsent(Map<String, AttributeValue> item) {
        try {
            putIfAbsent(item);
        } catch (ConditionalCheckFailedException alreadySeeded) {
            // Preserve any changes made through the API.
        }
    }

    private static Map<String, AttributeValue> toItem(OrderView order) {
        List<AttributeValue> items = order.items().stream()
                .map(item -> AttributeValue.fromM(Map.of(
                        "itemNumber", num(item.itemNumber()),
                        "productName", str(item.productName()),
                        "quantity", num(item.quantity()),
                        "unitPriceCents", num(item.unitPriceCents()),
                        "lineTotalCents", num(item.lineTotalCents()))))
                .toList();
        var record = new HashMap<>(Map.of(
                ID, str(order.id()),
                CUSTOMER, str(order.customerId()),
                SORT, str(sortKey(order.createdAt(), order.id())),
                "status", str(order.status()),
                "totalCents", num(order.totalCents()),
                "createdAt", num(order.createdAt().toEpochMilli()),
                "items", AttributeValue.fromL(items)));
        if (order.confirmedAt() != null) {
            record.put("confirmedAt", num(order.confirmedAt().toEpochMilli()));
        }
        return record;
    }

    private static OrderView toOrder(Map<String, AttributeValue> record) {
        OrderSummary summary = toSummary(record);
        Instant confirmedAt = record.containsKey("confirmedAt")
                ? Instant.ofEpochMilli(number(record, "confirmedAt")) : null;
        var items = record.get("items").l().stream().map(AttributeValue::m)
                .map(item -> new OrderItemView(
                        Math.toIntExact(number(item, "itemNumber")),
                        item.get("productName").s(),
                        Math.toIntExact(number(item, "quantity")),
                        number(item, "unitPriceCents"),
                        number(item, "lineTotalCents")))
                .toList();
        return new OrderView(summary.id(), summary.customerId(), summary.status(), summary.totalCents(),
                summary.createdAt(), confirmedAt, items);
    }

    private static OrderSummary toSummary(Map<String, AttributeValue> record) {
        return new OrderSummary(record.get(ID).s(), record.get(CUSTOMER).s(), record.get("status").s(),
                number(record, "totalCents"), Instant.ofEpochMilli(number(record, "createdAt")));
    }

    private static String sortKey(Instant createdAt, String id) {
        // Fixed-width hex preserves signed time ordering under a bytewise string comparison.
        return HexFormat.of().toHexDigits(createdAt.toEpochMilli() ^ Long.MIN_VALUE) + "#" + id;
    }

    private static boolean isOrderId(String id) {
        return id.length() == 36 && !id.startsWith("customer#");
    }

    private static String customerKey(String id) {
        return "customer#" + id;
    }

    private static Map<String, AttributeValue> key(String id) {
        return Map.of(ID, str(id));
    }

    private static AttributeValue str(String value) {
        return AttributeValue.fromS(value);
    }

    private static AttributeValue num(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    private static long number(Map<String, AttributeValue> record, String name) {
        return Long.parseLong(record.get(name).n());
    }
}
