package com.engineeringprooflabs.datamodeling.orders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Projections.include;

@Repository
@Profile("mongodb")
public class MongoOrderRepository implements OrderStore {

    private final MongoCollection<Document> customers;
    private final MongoCollection<Document> orders;

    public MongoOrderRepository(MongoClient mongoClient, @Value("${mongodb.database}") String database) {
        this.customers = mongoClient.getDatabase(database).getCollection("customers");
        this.orders = mongoClient.getDatabase(database).getCollection("orders");
    }

    @PostConstruct
    void ensureListingIndex() {
        orders.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("customerId"),
                        Indexes.descending("createdAt"),
                        Indexes.descending("_id")),
                new IndexOptions().name("customer_created_id"));
    }

    @Override
    public String backend() {
        return "mongodb";
    }

    @Override
    public boolean customerExists(String customerId) {
        return customers.find(eq("_id", customerId)).projection(include("_id")).first() != null;
    }

    @Override
    public void createOrder(OrderView order) {
        List<Document> items = new ArrayList<>(order.items().size());
        for (OrderItemView item : order.items()) {
            items.add(new Document("itemNumber", item.itemNumber())
                    .append("productName", item.productName())
                    .append("quantity", item.quantity())
                    .append("unitPriceCents", item.unitPriceCents())
                    .append("lineTotalCents", item.lineTotalCents()));
        }

        Document document = new Document("_id", order.id())
                .append("customerId", order.customerId())
                .append("status", order.status())
                .append("totalCents", order.totalCents())
                .append("createdAt", Date.from(order.createdAt()))
                .append("confirmedAt", null)
                .append("items", items);
        orders.insertOne(document);
    }

    @Override
    public Optional<OrderView> findById(String id) {
        Document document = orders.find(eq("_id", id)).first();
        return document == null ? Optional.empty() : Optional.of(toOrder(document));
    }

    @Override
    public int confirmPending(String id, Instant confirmedAt) {
        UpdateResult result = orders.updateOne(
                and(eq("_id", id), eq("status", "PENDING")),
                Updates.combine(
                        Updates.set("status", "CONFIRMED"),
                        Updates.set("confirmedAt", Date.from(confirmedAt))));
        return Math.toIntExact(result.getModifiedCount());
    }

    @Override
    public boolean orderExists(String id) {
        return orders.find(eq("_id", id)).projection(include("_id")).first() != null;
    }

    @Override
    public List<OrderSummary> listCustomerOrders(String customerId, CustomerOrderCursor cursor, int fetchLimit) {
        Bson filter = eq("customerId", customerId);
        if (cursor != null) {
            Date cursorTime = Date.from(cursor.createdAt());
            filter = and(filter, or(
                    lt("createdAt", cursorTime),
                    and(eq("createdAt", cursorTime), lt("_id", cursor.id().toString()))));
        }

        List<OrderSummary> results = new ArrayList<>(fetchLimit);
        for (Document document : orders.find(filter)
                .sort(Sorts.orderBy(Sorts.descending("createdAt"), Sorts.descending("_id")))
                .limit(fetchLimit)) {
            results.add(new OrderSummary(
                    document.getString("_id"),
                    document.getString("customerId"),
                    document.getString("status"),
                    document.getLong("totalCents"),
                    document.getDate("createdAt").toInstant()));
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private OrderView toOrder(Document document) {
        List<Document> storedItems = (List<Document>) document.get("items", List.class);
        List<OrderItemView> items = storedItems.stream()
                .map(item -> new OrderItemView(
                        item.getInteger("itemNumber"),
                        item.getString("productName"),
                        item.getInteger("quantity"),
                        item.getLong("unitPriceCents"),
                        item.getLong("lineTotalCents")))
                .toList();
        Date confirmedAt = document.getDate("confirmedAt");
        return new OrderView(
                document.getString("_id"),
                document.getString("customerId"),
                document.getString("status"),
                document.getLong("totalCents"),
                document.getDate("createdAt").toInstant(),
                confirmedAt == null ? null : confirmedAt.toInstant(),
                items);
    }
}
