# One Orders API, Four Databases

This lab compares the same orders API on PostgreSQL, MongoDB, and DynamoDB Local. Oracle is the next database in the comparison. Each adapter exposes its database operations directly in the code.

The PostgreSQL slice uses PostgreSQL 17.12, Java 21, Spring Boot 4.2.0, Flyway 11.20.0, and the PostgreSQL JDBC driver 42.7.8. The MongoDB slice uses MongoDB 8.0.38 and the synchronous Java driver 5.15.0. Docker Compose profiles start only the selected database and API. Database and Java images use fixed version tags.

## Run the PostgreSQL slice

Requirements: Docker Engine and Docker Compose v2.

```sh
docker compose --profile postgres up --build -d
docker compose --profile postgres ps
```

The API waits for PostgreSQL health and applies the versioned migration in `src/main/resources/db/migration` during startup. Allow several seconds for Spring to finish starting; `docker compose --profile postgres logs -f api-postgres` shows the `Started OrdersApiApplication` line. If the selected database cannot be reached, startup fails instead of silently switching storage.

Create an order with two items:

```sh
curl -i http://localhost:18081/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId": "customer-001",
    "items": [
      {"productName": "Notebook", "quantity": 2, "unitPriceCents": 12990},
      {"productName": "Pen set", "quantity": 1, "unitPriceCents": 1590}
    ]
  }'
```

The response is `201 Created`, includes a `Location` header and returns the server-calculated total (`27570` cents). Copy its `id` into the next request:

```sh
curl -i http://localhost:18081/orders/<order-id>
docker compose --profile postgres restart api-postgres
curl -i http://localhost:18081/orders/<order-id>
```

The second read demonstrates that the order and its items survive an API restart. Check the stored rows directly:

```sh
docker compose --profile postgres exec -T postgres psql -U orders_app -d orders -c \
  "SELECT o.id, o.customer_id, o.status, o.total_cents, o.created_at,
          i.item_number, i.product_name, i.quantity, i.unit_price_cents, i.line_total_cents
   FROM orders o JOIN order_items i ON i.order_id = o.id
   WHERE o.id = '<order-id>'::uuid ORDER BY i.item_number;"
```

An unknown customer returns `404 CUSTOMER_NOT_FOUND`. A missing order returns `404 ORDER_NOT_FOUND`. Invalid item counts, names, quantities, or prices return `400 VALIDATION_ERROR`.

### Prove that a partial create rolls back

The local Compose environment enables one lab-only failure hook. The default application setting disables it. Do not expose this lab endpoint outside a local verification environment.

Record the count, send a create request with the failure header, and check the count again. The request deliberately fails after inserting the order header and before inserting its items; Spring rolls back the transaction.

```sh
docker compose --profile postgres exec -T postgres psql -U orders_app -d orders -Atc \
  "SELECT count(*) FROM orders;"

curl -i http://localhost:18081/orders \
  -H 'Content-Type: application/json' \
  -H 'X-Lab-Fail-After-Order: true' \
  -d '{"customerId":"customer-001","items":[{"productName":"Rollback probe","quantity":1,"unitPriceCents":100}]}'

docker compose --profile postgres exec -T postgres psql -U orders_app -d orders -Atc \
  "SELECT count(*) FROM orders;"
```

The failure response is `500 INJECTED_WRITE_FAILURE`; the two counts must match. Also verify the missing-order case:

```sh
curl -i http://localhost:18081/orders/00000000-0000-0000-0000-000000000000
```

## Confirm an order and page customer history

`POST /orders/{id}/confirm` changes `PENDING` to `CONFIRMED` with one conditional SQL update. It returns `200` on the first transition, `409 ORDER_ALREADY_CONFIRMED` on a repeated confirmation, and `404` for an unknown order. The database update itself is the concurrency guard; there is no in-process lock.

`GET /customers/{id}/orders?limit=...&cursor=...` returns summaries in descending `createdAt`, then descending UUID order. The default page size is 10 and the allowed range is 1–50. The opaque cursor is bound to the active backend, customer, and page size. Pagination is stable for a fixed dataset; it does not hold a snapshot while concurrent writes occur.

The fixture uses four orders for `customer-003`, including two pairs with matching timestamps. From PowerShell 7, seed it and run the checks:

```powershell
Get-Content -Raw .\scripts\seed-postgres.sql | docker compose --profile postgres exec -T postgres psql -U orders_app -d orders
.\scripts\verify-pagination.ps1
.\scripts\verify-concurrency.ps1 -Iterations 10
```

The concurrency verifier first confirms two different pending orders at the same time and expects `200` for both. It then creates a fresh pending order for each iteration, waits until two separate HTTP clients reach a barrier, releases both confirmation requests together, and sends each request once without retries. Every iteration must produce one `200` and one `409`, then a point read of `CONFIRMED`; a later retry must return `409` without changing `confirmedAt`.

The listing index is `idx_orders_customer_created_id` on `(customer_id, created_at DESC, id DESC)`. The explain script shows both the first-page query and a later cursor seek with a temporary 20,000-row fixture, then rolls those rows back. In the observed run, both plans used this index and returned 11 rows; this is plan evidence from one local run, not a performance benchmark.

```powershell
Get-Content -Raw .\scripts\explain-customer-orders.sql | docker compose --profile postgres exec -T postgres psql -U orders_app -d orders
```

## Run the MongoDB slice

Stop the current profile before switching databases so both APIs do not compete for port 18081:

```sh
docker compose --profile "*" down --remove-orphans
docker compose --profile mongodb up --build -d
docker compose --profile mongodb ps
```

MongoDB initializes three customers and four deterministic orders from `scripts/mongo-init.js` when its data volume is first created. The API creates the `customer_created_id` compound index at startup. A new order and its item array are written with one `insertOne`; the document is the atomic write unit in this model.

Inspect a seeded document and its embedded item:

```sh
docker compose --profile mongodb exec mongodb mongosh orders --quiet --eval 'db.orders.findOne({_id:"30000000-0000-4000-8000-000000000001"})'
```

Create and read an order with the same HTTP requests shown above. Confirm the returned ID and inspect the document after its state transition:

```sh
curl -i -X POST http://localhost:18081/orders/<order-id>/confirm
docker compose --profile mongodb exec mongodb mongosh orders --quiet --eval 'db.orders.findOne({_id:"<order-id>"},{_id:1,status:1,confirmedAt:1,items:1})'
```

The adapter changes the state with `updateOne({_id, status: "PENDING"}, ...)`; the database applies this update atomically to one document. The second confirmation returns 409. The shared HTTP verifiers run unchanged against this profile:

```powershell
.\scripts\verify-pagination.ps1
.\scripts\verify-concurrency.ps1 -Iterations 10
```

MongoDB stores the order and its bounded, immutable item list together because creation, retrieval, and confirmation use the order as one unit. Separate item documents could make sense if item collections needed independent growth, queries, or lifecycle; that would add multi-document consistency work to the current write path. The common API clock truncates timestamps to milliseconds to match BSON Date precision.

The PostgreSQL-only failure hook is disabled for MongoDB. A single document insert is the atomicity demonstration here; the PostgreSQL hook instead demonstrates rollback across separate order and item rows.

## Run the DynamoDB Local slice

```sh
docker compose --profile "*" down --remove-orphans
docker compose --profile dynamodb up --build -d
docker compose --profile dynamodb logs -f api-dynamodb
```

The profile starts DynamoDB Local 3.3.2 and the API with AWS SDK for Java 2.55.15. A setup container makes the database volume writable by the image's service account. The SDK targets the Compose service with static local credentials and region `us-east-1`. On startup, the adapter creates the table and inserts missing fixtures without replacing existing orders. Wait for the API's startup line, then run the shared PowerShell checks.

```powershell
.\scripts\verify-pagination.ps1
.\scripts\verify-concurrency.ps1 -Iterations 10
```

| Operation | Access | Result |
| --- | --- | --- |
| Create | Conditional `PutItem` by order UUID | One item containing the order and its line items |
| Get | Strong `GetItem` by order UUID | Full order |
| Confirm | `UpdateItem` conditioned on `PENDING` | One state transition, followed by a strong read |
| List by customer | Descending `Query` on the `customer-created` global secondary index | Order summaries, eventually consistent |

The index partitions by `customerId` and orders by `createdOrder`, a fixed-width timestamp followed by the UUID. The HTTP cursor identifies the last summary delivered. The adapter reconstructs `ExclusiveStartKey` from it, so the extra record used to detect another page is available on the next request.

Inspect the table and index through the local DynamoDB API:

```sh
docker compose --profile dynamodb exec -T dynamodb curl -fsS \
  --aws-sigv4 aws:amz:us-east-1:dynamodb --user local:local \
  -H 'Content-Type: application/x-amz-json-1.0' \
  -H 'X-Amz-Target: DynamoDB_20120810.DescribeTable' \
  -d '{"TableName":"orders-lab"}' http://localhost:8000/
```

The local run demonstrates key selection, pagination, and conditional writes. Managed DynamoDB throughput, physical distribution, propagation delay, and latency require separate measurements. [DynamoDB Local usage notes](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.UsageNotes.html)

## Stop and reset

Stop the services and keep database data:

```sh
docker compose --profile "*" down --remove-orphans
```

Remove the local database volume and all lab data:

```sh
docker compose --profile "*" down --volumes --remove-orphans
```

The Compose credentials and exposed ports are for local learning only.

## Current behavior

- Implemented: `POST /orders` with customer/item validation, server-calculated totals, PostgreSQL transaction, and item inserts.
- Implemented: `GET /orders/{id}` with order and item data; missing IDs return 404.
- Implemented: conditional confirmation with `200`, `409`, and `404` outcomes, plus customer-order keyset pagination with a customer-scoped opaque cursor.
- Implemented: MongoDB document storage with embedded items, conditional confirmation, and customer-order keyset pagination.
- Implemented: DynamoDB Local item storage, conditional confirmation, and pagination on a customer index.
- Pending: Oracle and the cross-customer list of recent pending orders.

Money is stored as integer cents, timestamps are stored as UTC instants, and item rows are immutable after creation. The common limit is 20 items, 999 units per item, 120 characters per product name, and at most 100,000,000 cents per unit. These bounds keep the same order representable in the later database models.
