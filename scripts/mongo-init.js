const ordersDatabase = db.getSiblingDB("orders");
const customers = ordersDatabase.customers;
const orders = ordersDatabase.orders;

for (const customer of [
  { _id: "customer-001", name: "Avery Stone" },
  { _id: "customer-002", name: "Morgan Lee" },
  { _id: "customer-003", name: "Taylor Quinn" },
]) {
  customers.updateOne({ _id: customer._id }, { $setOnInsert: customer }, { upsert: true });
}

for (const order of [
  {
    _id: "30000000-0000-4000-8000-000000000001",
    customerId: "customer-003",
    status: "PENDING",
    totalCents: NumberLong(1000),
    createdAt: ISODate("2026-11-20T12:00:00Z"),
    confirmedAt: null,
    items: [{ itemNumber: 1, productName: "Fixture item 1", quantity: 1, unitPriceCents: NumberLong(1000), lineTotalCents: NumberLong(1000) }],
  },
  {
    _id: "30000000-0000-4000-8000-000000000002",
    customerId: "customer-003",
    status: "PENDING",
    totalCents: NumberLong(2000),
    createdAt: ISODate("2026-11-20T12:00:00Z"),
    confirmedAt: null,
    items: [{ itemNumber: 1, productName: "Fixture item 2", quantity: 1, unitPriceCents: NumberLong(2000), lineTotalCents: NumberLong(2000) }],
  },
  {
    _id: "30000000-0000-4000-8000-000000000003",
    customerId: "customer-003",
    status: "CONFIRMED",
    totalCents: NumberLong(3000),
    createdAt: ISODate("2026-11-19T12:00:00Z"),
    confirmedAt: ISODate("2026-11-19T12:05:00Z"),
    items: [{ itemNumber: 1, productName: "Fixture item 3", quantity: 1, unitPriceCents: NumberLong(3000), lineTotalCents: NumberLong(3000) }],
  },
  {
    _id: "30000000-0000-4000-8000-000000000004",
    customerId: "customer-003",
    status: "CONFIRMED",
    totalCents: NumberLong(4000),
    createdAt: ISODate("2026-11-19T12:00:00Z"),
    confirmedAt: ISODate("2026-11-19T12:06:00Z"),
    items: [{ itemNumber: 1, productName: "Fixture item 4", quantity: 1, unitPriceCents: NumberLong(4000), lineTotalCents: NumberLong(4000) }],
  },
]) {
  orders.updateOne({ _id: order._id }, { $setOnInsert: order }, { upsert: true });
}
