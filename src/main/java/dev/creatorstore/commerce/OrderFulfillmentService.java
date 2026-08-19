package dev.creatorstore.commerce;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderFulfillmentService {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private static final SecureRandom RANDOM = new SecureRandom();

  public OrderFulfillmentService(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }

  /**
   * Turns a webhook-confirmed paid checkout session into a real order + customer row.
   * Idempotent: re-delivered webhooks for an already-fulfilled session are a no-op.
   */
  /** Returns the buyer's access token for this session (existing or newly created), or null if the session is unknown. */
  @Transactional
  public String recordPaidOrder(String provider, String providerSessionId) {
    List<Map<String, Object>> rows = db.queryForList(
        "select id,creator_id,product_id,amount_subunits,buyer_email,buyer_name,order_id,field_responses,slot_id,plan_id from checkout_sessions "
            + "where provider=? and provider_session_id=? for update",
        provider, providerSessionId);
    if (rows.isEmpty()) return null;
    Map<String, Object> session = rows.get(0);
    if (session.get("order_id") != null) {
      List<String> existing = db.query("select access_token from entitlements where order_id=?",
          (rs, i) -> rs.getString("access_token"), session.get("order_id"));
      return existing.isEmpty() ? null : existing.get(0);
    }

    long creatorId = ((Number) session.get("creator_id")).longValue();
    long productId = ((Number) session.get("product_id")).longValue();
    int amount = ((Number) session.get("amount_subunits")).intValue();
    String email = session.get("buyer_email") == null ? "" : String.valueOf(session.get("buyer_email")).trim().toLowerCase();
    String name = session.get("buyer_name") == null ? "" : String.valueOf(session.get("buyer_name")).trim();

    Long customerId = null;
    if (!email.isBlank()) {
      db.update("insert into customers(creator_id,name,email,source) values(?,?,?,'checkout') "
              + "on conflict(creator_id,email) do update set name=excluded.name",
          creatorId, name.isBlank() ? email : name, email);
      customerId = db.queryForObject("select id from customers where creator_id=? and email=?", Long.class, creatorId, email);
    }

    db.update("insert into orders(creator_id,customer_id,product_id,amount_cents,fee_cents,status,external_payment_id) "
            + "values(?,?,?,?,0,'paid',?)",
        creatorId, customerId, productId, amount, providerSessionId);
    long orderId = db.queryForObject(
        "select id from orders where creator_id=? and external_payment_id=? order by id desc limit 1",
        Long.class, creatorId, providerSessionId);

    db.update("update checkout_sessions set order_id=?,status='paid',updated_at=current_timestamp where id=?", orderId, session.get("id"));

    Object slotId = session.get("slot_id");
    if (slotId != null) db.update("update bookings set customer_id=?,status='confirmed' where id=? and status='open'", customerId, slotId);

    Object planId = session.get("plan_id");
    if (planId != null) {
      Map<String, Object> plan = db.queryForMap("select interval_name,interval_count from product_payment_plans where id=?", planId);
      db.update("insert into membership_subscriptions(product_id,customer_id,order_id,plan_id,current_period_end) "
              + "values(?,?,?,?, current_timestamp + (? || ' ' || ?)::interval)",
          productId, customerId, orderId, planId, ((Number) plan.get("interval_count")).intValue(), plan.get("interval_name"));
    }

    String productType = db.queryForObject("select type from products where id=?", String.class, productId);
    if ("webinar".equals(productType)) {
      List<Long> upcoming = db.query(
          "select id from webinar_sessions where product_id=? and starts_at > current_timestamp order by starts_at limit 1",
          (rs, i) -> rs.getLong("id"), productId);
      if (!upcoming.isEmpty())
        db.update("insert into webinar_registrations(session_id,customer_id,order_id) values(?,?,?)", upcoming.get(0), customerId, orderId);
    }
    if ("course".equals(productType))
      db.update("insert into course_enrollments(product_id,customer_id,order_id) values(?,?,?)", productId, customerId, orderId);

    Object fieldResponsesRaw = session.get("field_responses");
    if (fieldResponsesRaw != null && !String.valueOf(fieldResponsesRaw).isBlank()) {
      try {
        Map<String, String> responses = json.readValue(String.valueOf(fieldResponsesRaw), new TypeReference<Map<String, String>>() {});
        for (var entry : responses.entrySet())
          db.update("insert into order_field_responses(order_id,field_id,value) values(?,?,?)", orderId, Long.parseLong(entry.getKey()), entry.getValue());
      } catch (Exception ignored) { /* malformed field data must not block fulfillment */ }
    }

    String accessToken = newAccessToken();
    db.update("insert into entitlements(creator_id,product_id,order_id,customer_id,access_token) values(?,?,?,?,?)",
        creatorId, productId, orderId, customerId, accessToken);
    return accessToken;
  }

  private static String newAccessToken() {
    byte[] bytes = new byte[24];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
