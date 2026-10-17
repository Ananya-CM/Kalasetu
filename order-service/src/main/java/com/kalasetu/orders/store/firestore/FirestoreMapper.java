package com.kalasetu.orders.store.firestore;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentSnapshot;
import com.kalasetu.orders.domain.IdempotencyRecord;
import com.kalasetu.orders.domain.LedgerEntry;
import com.kalasetu.orders.domain.Money;
import com.kalasetu.orders.domain.Order;
import com.kalasetu.orders.domain.OrderItem;
import com.kalasetu.orders.domain.OrderStatus;
import com.kalasetu.orders.domain.Product;
import com.kalasetu.orders.domain.ShippingDetails;
import com.kalasetu.orders.domain.StatusChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Converts between domain objects and Firestore documents. */
final class FirestoreMapper {

    private static final Logger log = LoggerFactory.getLogger(FirestoreMapper.class);

    private FirestoreMapper() {
    }

    /**
     * Reads a product written by the existing Kalasetu app ({@code userId}, {@code name}, {@code price}
     * in rupees, optional {@code stock}). Returns empty if the document is missing or unusable.
     */
    static Optional<Product> toProduct(DocumentSnapshot snapshot) {
        if (!snapshot.exists()) {
            return Optional.empty();
        }
        String sellerId = snapshot.getString("userId");
        String name = snapshot.getString("name");
        if (sellerId == null || name == null) {
            log.warn("Product {} is missing userId or name", snapshot.getId());
            return Optional.empty();
        }
        long pricePaise;
        try {
            pricePaise = Money.rupeesToPaise(snapshot.get("price"));
        } catch (IllegalArgumentException e) {
            log.warn("Product {} has an invalid price: {}", snapshot.getId(), e.getMessage());
            return Optional.empty();
        }
        Object rawStock = snapshot.get("stock");
        Long stock = rawStock instanceof Number number ? Math.max(0L, number.longValue()) : null;
        return Optional.of(new Product(snapshot.getId(), sellerId, name, pricePaise, stock));
    }

    static Map<String, Object> fromOrder(Order order) {
        Map<String, Object> data = new HashMap<>();
        data.put("buyerId", order.buyerId());
        data.put("items", order.items().stream().map(FirestoreMapper::fromItem).toList());
        data.put("sellerIds", order.sellerIds());
        data.put("totalPaise", order.totalPaise());
        data.put("currency", order.currency());
        data.put("shipping", Map.of(
                "name", order.shipping().name(),
                "email", order.shipping().email(),
                "address", order.shipping().address()));
        data.put("status", order.status().name());
        data.put("gatewayOrderId", order.gatewayOrderId());
        data.put("gatewayPaymentId", order.gatewayPaymentId());
        data.put("statusHistory", order.statusHistory().stream().map(FirestoreMapper::fromStatusChange).toList());
        data.put("createdAt", toTimestamp(order.createdAt()));
        data.put("updatedAt", toTimestamp(order.updatedAt()));
        data.put("expiresAt", toTimestamp(order.expiresAt()));
        return data;
    }

    @SuppressWarnings("unchecked")
    static Optional<Order> toOrder(DocumentSnapshot snapshot) {
        if (!snapshot.exists()) {
            return Optional.empty();
        }
        String statusName = snapshot.getString("status");
        if (statusName == null || !isKnownStatus(statusName)) {
            // Orders written by the original browser checkout use a different shape; they are not managed here.
            return Optional.empty();
        }
        List<Map<String, Object>> items = (List<Map<String, Object>>) snapshot.get("items");
        List<Map<String, Object>> history = (List<Map<String, Object>>) snapshot.get("statusHistory");
        Map<String, Object> shipping = (Map<String, Object>) snapshot.get("shipping");
        return Optional.of(new Order(
                snapshot.getId(),
                snapshot.getString("buyerId"),
                items.stream().map(FirestoreMapper::toItem).toList(),
                snapshot.getLong("totalPaise"),
                snapshot.getString("currency"),
                new ShippingDetails(
                        (String) shipping.get("name"),
                        (String) shipping.get("email"),
                        (String) shipping.get("address")),
                OrderStatus.valueOf(statusName),
                snapshot.getString("gatewayOrderId"),
                snapshot.getString("gatewayPaymentId"),
                history.stream().map(FirestoreMapper::toStatusChange).toList(),
                toInstant(snapshot.getTimestamp("createdAt")),
                toInstant(snapshot.getTimestamp("updatedAt")),
                toInstant(snapshot.getTimestamp("expiresAt"))));
    }

    static Map<String, Object> fromIdempotencyRecord(IdempotencyRecord idempotencyRecord) {
        return Map.of(
                "orderId", idempotencyRecord.orderId(),
                "requestFingerprint", idempotencyRecord.requestFingerprint(),
                "createdAt", toTimestamp(idempotencyRecord.createdAt()));
    }

    static Optional<IdempotencyRecord> toIdempotencyRecord(DocumentSnapshot snapshot) {
        if (!snapshot.exists()) {
            return Optional.empty();
        }
        return Optional.of(new IdempotencyRecord(
                snapshot.getString("orderId"),
                snapshot.getString("requestFingerprint"),
                toInstant(snapshot.getTimestamp("createdAt"))));
    }

    static Map<String, Object> fromLedgerEntry(LedgerEntry entry) {
        return Map.of(
                "orderId", entry.orderId(),
                "sellerId", entry.sellerId(),
                "amountPaise", entry.amountPaise(),
                "type", entry.type().name(),
                "createdAt", toTimestamp(entry.createdAt()));
    }

    static Timestamp toTimestamp(Instant instant) {
        return Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano());
    }

    private static Instant toInstant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private static Map<String, Object> fromItem(OrderItem item) {
        return Map.of(
                "productId", item.productId(),
                "sellerId", item.sellerId(),
                "name", item.name(),
                "unitPricePaise", item.unitPricePaise(),
                "quantity", item.quantity(),
                "lineTotalPaise", item.lineTotalPaise());
    }

    private static OrderItem toItem(Map<String, Object> data) {
        return new OrderItem(
                (String) data.get("productId"),
                (String) data.get("sellerId"),
                (String) data.get("name"),
                ((Number) data.get("unitPricePaise")).longValue(),
                ((Number) data.get("quantity")).intValue());
    }

    private static Map<String, Object> fromStatusChange(StatusChange change) {
        return Map.of(
                "status", change.status().name(),
                "at", toTimestamp(change.at()),
                "actor", change.actor());
    }

    private static StatusChange toStatusChange(Map<String, Object> data) {
        return new StatusChange(
                OrderStatus.valueOf((String) data.get("status")),
                toInstant((Timestamp) data.get("at")),
                (String) data.get("actor"));
    }

    private static boolean isKnownStatus(String statusName) {
        for (OrderStatus status : OrderStatus.values()) {
            if (status.name().equals(statusName)) {
                return true;
            }
        }
        return false;
    }
}
