package dev.creatorstore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.creatorstore.commerce.OrderFulfillmentService;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
class ExternalIntegrationController {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final OrderFulfillmentService fulfillment;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Value("${payments.mode:disabled}") String paymentMode;
  @Value("${payments.default-provider:razorpay}") String defaultProvider;
  @Value("${payments.success-url}") String successUrl;
  @Value("${payments.cancel-url}") String cancelUrl;
  @Value("${payments.razorpay.key-id:}") String razorpayKeyId;
  @Value("${payments.razorpay.key-secret:}") String razorpayKeySecret;
  @Value("${payments.razorpay.webhook-secret:}") String razorpayWebhookSecret;
  @Value("${payments.stripe.secret-key:}") String stripeSecretKey;
  @Value("${payments.stripe.webhook-secret:}") String stripeWebhookSecret;

  @Value("${instagram.mode:disabled}") String instagramMode;
  @Value("${instagram.graph-api-version:v23.0}") String instagramApiVersion;
  @Value("${instagram.account-id:}") String instagramAccountId;
  @Value("${instagram.access-token:}") String instagramAccessToken;
  @Value("${instagram.app-secret:}") String instagramAppSecret;
  @Value("${instagram.verify-token:}") String instagramVerifyToken;
  @Value("${instagram.test-recipient-ids:}") String instagramTestRecipientIds;

  ExternalIntegrationController(JdbcTemplate db, ObjectMapper json, OrderFulfillmentService fulfillment) {
    this.db = db;
    this.json = json;
    this.fulfillment = fulfillment;
  }

  @GetMapping("/api/v1/payments/config")
  Map<String,Object> paymentConfig() {
    return Map.of(
        "real_money", true,
        "mode", paymentMode,
        "default_provider", defaultProvider,
        "default_currency", "INR",
        "smallest_unit", "paise",
        "providers", List.of(
            Map.of("id", "razorpay", "label", "Razorpay", "india_primary", true, "configured", razorpayConfigured()),
            Map.of("id", "stripe", "label", "Stripe", "india_primary", false, "configured", stripeConfigured())),
        "notice", "External real-money integration. Disabled mode never creates or confirms a charge.");
  }

  @PostMapping("/api/v1/checkout/sessions")
  ResponseEntity<?> createCheckout(@RequestHeader(value="Idempotency-Key", defaultValue="") String idempotencyKey,
                                   @RequestBody CheckoutIn input) {
    if (!Set.of("test", "live").contains(paymentMode)) return unavailable("Payments are disabled; no charge was attempted.");
    if (idempotencyKey.isBlank() || idempotencyKey.length() > 120)
      return ResponseEntity.badRequest().body(Map.of("error", "Idempotency-Key is required and must be at most 120 characters."));
    if (input.buyerEmail()==null || !input.buyerEmail().contains("@"))
      return ResponseEntity.badRequest().body(Map.of("error", "A valid buyerEmail is required."));
    List<Map<String,Object>> existing = db.queryForList("select id,provider,provider_session_id,currency,amount_subunits,status from checkout_sessions where creator_id=? and idempotency_key=?", input.creatorId(), idempotencyKey);
    if (!existing.isEmpty()) return ResponseEntity.ok(existing.get(0));

    List<Map<String,Object>> rows = db.queryForList("select p.id,p.creator_id,p.title,p.price_cents as amount_subunits,s.currency,c.handle from products p join stores s on s.creator_id=p.creator_id join creators c on c.id=p.creator_id where p.id=? and p.creator_id=? and p.status='published'", input.productId(), input.creatorId());
    if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Published product not found for this creator."));
    Map<String,Object> product = rows.get(0);
    String currency = String.valueOf(product.get("currency")).trim().toUpperCase(Locale.ROOT);
    if (!currency.equals("INR")) return ResponseEntity.badRequest().body(Map.of("error", "India launch checkout currently requires INR."));
    int amount = ((Number) product.get("amount_subunits")).intValue();
    if (input.planId() != null) {
      List<Map<String,Object>> plans = db.queryForList("select amount_cents from product_payment_plans where id=? and product_id=?", input.planId(), input.productId());
      if (plans.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Plan not found for this product."));
      amount = ((Number) plans.get(0).get("amount_cents")).intValue();
    }
    if (amount <= 0) return ResponseEntity.badRequest().body(Map.of("error", "Free products do not use a payment gateway."));
    String provider = optional(input.provider(), defaultProvider).toLowerCase(Locale.ROOT);
    if (!Set.of("razorpay", "stripe").contains(provider)) return ResponseEntity.badRequest().body(Map.of("error", "provider must be razorpay or stripe"));
    if (provider.equals("razorpay") && !razorpayConfigured()) return unavailable("Razorpay test/live credentials are not configured; no charge was attempted.");
    if (provider.equals("stripe") && !stripeConfigured()) return unavailable("Stripe test/live credentials are not configured; no charge was attempted.");

    String checkoutId = UUID.randomUUID().toString();
    String fieldResponsesJson = toJsonOrNull(input.fieldResponses());
    try {
      db.update("insert into checkout_sessions(id,creator_id,product_id,provider,idempotency_key,currency,amount_subunits,status,buyer_email,buyer_name,field_responses,slot_id,plan_id) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
          checkoutId, input.creatorId(), input.productId(), provider, idempotencyKey, currency, amount, "creating", input.buyerEmail().trim().toLowerCase(), optional(input.buyerName(), ""), fieldResponsesJson, input.slotId(), input.planId());
    } catch (DataIntegrityViolationException concurrentRequest) {
      return ResponseEntity.ok(db.queryForMap("select id,provider,provider_session_id,currency,amount_subunits,status from checkout_sessions where creator_id=? and idempotency_key=?", input.creatorId(), idempotencyKey));
    }
    try {
      ProviderSession remote = provider.equals("razorpay")
          ? createRazorpayOrder(checkoutId, amount, currency)
          : createStripeSession(checkoutId, String.valueOf(product.get("title")), amount, currency, idempotencyKey);
      db.update("update checkout_sessions set provider_session_id=?,status='provider_created',updated_at=current_timestamp where id=?", remote.id(), checkoutId);
      Map<String,Object> response = new LinkedHashMap<>();
      response.put("checkout_id", checkoutId);
      response.put("provider", provider);
      response.put("mode", paymentMode);
      response.put("currency", currency);
      response.put("amount_subunits", amount);
      response.put("provider_session_id", remote.id());
      if (provider.equals("razorpay")) {
        response.put("public_key_id", razorpayKeyId);
        response.put("checkout_script", "https://checkout.razorpay.com/v1/checkout.js");
      } else response.put("redirect_url", remote.redirectUrl());
      return ResponseEntity.status(201).body(response);
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      db.update("update checkout_sessions set status='provider_failed',updated_at=current_timestamp where id=?", checkoutId);
      return ResponseEntity.status(502).body(Map.of("error", "Payment provider request failed; no local paid order was created."));
    }
  }

  @PostMapping("/api/v1/payments/razorpay/verify")
  ResponseEntity<?> verifyRazorpayBrowserReturn(@RequestBody RazorpayReturn input) {
    if (!razorpayConfigured() || input.orderId()==null || input.paymentId()==null || input.signature()==null)
      return ResponseEntity.status(401).body(Map.of("verified", false));
    boolean verified = Signatures.verifyHexHmac(razorpayKeySecret, input.orderId()+"|"+input.paymentId(), input.signature());
    if (!verified) return ResponseEntity.status(401).body(Map.of("verified", false));
    db.update("update checkout_sessions set status='browser_verified',updated_at=current_timestamp where provider='razorpay' and provider_session_id=?", input.orderId());
    String accessToken = fulfillment.recordPaidOrder("razorpay", input.orderId());
    Map<String,Object> body = new LinkedHashMap<>();
    body.put("verified", true);
    body.put("final_status_source", "verified_webhook");
    if (accessToken != null) body.put("access_token", accessToken);
    return ResponseEntity.ok(body);
  }

  @PostMapping("/api/v1/webhooks/razorpay")
  ResponseEntity<?> razorpayWebhook(@RequestHeader(value="X-Razorpay-Signature", defaultValue="") String signature,
                                    @RequestBody byte[] raw) {
    if (razorpayWebhookSecret.isBlank() || !Signatures.verifyHexHmac(razorpayWebhookSecret, raw, signature))
      return ResponseEntity.status(401).body(Map.of("error", "invalid signature"));
    return recordPaymentEvent("razorpay", raw);
  }

  @PostMapping("/api/v1/webhooks/stripe")
  ResponseEntity<?> stripeWebhook(@RequestHeader(value="Stripe-Signature", defaultValue="") String signature,
                                  @RequestBody byte[] raw) {
    if (stripeWebhookSecret.isBlank() || !Signatures.verifyStripe(stripeWebhookSecret, raw, signature, Instant.now().getEpochSecond()))
      return ResponseEntity.status(401).body(Map.of("error", "invalid signature"));
    return recordPaymentEvent("stripe", raw);
  }

  @GetMapping("/api/v1/integrations/instagram/config")
  Map<String,Object> instagramConfig() {
    return Map.of(
        "external_service", true,
        "mode", instagramMode,
        "configured", instagramConfigured(),
        "send_enabled", Set.of("test", "live").contains(instagramMode) && instagramConfigured(),
        "test_recipient_count", testRecipients().size(),
        "required_permission", "instagram_business_manage_messages",
        "notice", "In test mode, messages can only be sent to allowlisted recipients who first messaged the professional account.");
  }

  @GetMapping("/api/v1/webhooks/instagram")
  ResponseEntity<String> verifyInstagramWebhook(@RequestParam(name="hub.mode", required=false) String mode,
                                                 @RequestParam(name="hub.verify_token", required=false) String token,
                                                 @RequestParam(name="hub.challenge", required=false) String challenge) {
    if ("subscribe".equals(mode) && !instagramVerifyToken.isBlank() && MessageDigest.isEqual(instagramVerifyToken.getBytes(StandardCharsets.UTF_8), optional(token, "").getBytes(StandardCharsets.UTF_8)))
      return ResponseEntity.ok(optional(challenge, ""));
    return ResponseEntity.status(403).body("verification failed");
  }

  @PostMapping("/api/v1/webhooks/instagram")
  ResponseEntity<?> instagramWebhook(@RequestHeader(value="X-Hub-Signature-256", defaultValue="") String signature,
                                     @RequestBody byte[] raw) {
    String provided = signature.startsWith("sha256=") ? signature.substring(7) : "";
    if (instagramAppSecret.isBlank() || !Signatures.verifyHexHmac(instagramAppSecret, raw, provided))
      return ResponseEntity.status(401).body(Map.of("error", "invalid signature"));
    String hash = Signatures.sha256(raw);
    try { db.update("insert into instagram_events(event_key,payload_sha256,signature_verified) values(?,?,true)", hash, hash); }
    catch (DataIntegrityViolationException duplicate) { return ResponseEntity.ok(Map.of("received", true, "duplicate", true)); }
    return ResponseEntity.ok(Map.of("received", true));
  }

  @PostMapping("/api/v1/integrations/instagram/test-message")
  ResponseEntity<?> sendInstagramTest(@RequestHeader(value="X-Confirm-External-Send", defaultValue="false") boolean confirmed,
                                      @RequestBody InstagramMessage input) {
    if (!confirmed) return ResponseEntity.status(428).body(Map.of("error", "X-Confirm-External-Send: true is required."));
    if (!Set.of("test", "live").contains(instagramMode) || !instagramConfigured()) return unavailable("Instagram messaging is disabled or not configured.");
    if (input.recipientId()==null || input.text()==null || input.text().isBlank() || input.text().length()>1000)
      return ResponseEntity.badRequest().body(Map.of("error", "recipientId and a 1-1000 character text are required."));
    if (instagramMode.equals("test") && !testRecipients().contains(input.recipientId()))
      return ResponseEntity.status(403).body(Map.of("error", "Recipient is not in INSTAGRAM_TEST_RECIPIENT_IDS."));
    try {
      String body = json.writeValueAsString(Map.of("recipient", Map.of("id", input.recipientId()), "message", Map.of("text", input.text())));
      HttpRequest request = HttpRequest.newBuilder(URI.create("https://graph.instagram.com/"+instagramApiVersion+"/"+instagramAccountId+"/messages"))
          .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer "+instagramAccessToken).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body)).build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode()/100 != 2) return ResponseEntity.status(502).body(Map.of("error", "Instagram rejected the test send.", "provider_status", response.statusCode()));
      JsonNode result = json.readTree(response.body());
      return ResponseEntity.ok(Map.of("sent", true, "recipient_id", result.path("recipient_id").asText(), "message_id", result.path("message_id").asText(), "external_service", "instagram"));
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      return ResponseEntity.status(502).body(Map.of("error", "Instagram request failed."));
    }
  }

  private ProviderSession createRazorpayOrder(String receipt, int amount, String currency) throws Exception {
    String body = json.writeValueAsString(Map.of("amount", amount, "currency", currency, "receipt", receipt));
    String basic = Base64.getEncoder().encodeToString((razorpayKeyId+":"+razorpayKeySecret).getBytes(StandardCharsets.UTF_8));
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.razorpay.com/v1/orders")).timeout(Duration.ofSeconds(10))
        .header("Authorization", "Basic "+basic).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode()/100 != 2) throw new IllegalStateException("razorpay status "+response.statusCode());
    return new ProviderSession(json.readTree(response.body()).path("id").asText(), null);
  }

  private ProviderSession createStripeSession(String checkoutId, String title, int amount, String currency, String idempotencyKey) throws Exception {
    Map<String,String> fields = new LinkedHashMap<>();
    fields.put("mode", "payment"); fields.put("success_url", successUrl); fields.put("cancel_url", cancelUrl);
    fields.put("client_reference_id", checkoutId); fields.put("line_items[0][quantity]", "1");
    fields.put("line_items[0][price_data][currency]", currency.toLowerCase(Locale.ROOT));
    fields.put("line_items[0][price_data][unit_amount]", String.valueOf(amount));
    fields.put("line_items[0][price_data][product_data][name]", title);
    String form = fields.entrySet().stream().map(e -> encode(e.getKey())+"="+encode(e.getValue())).reduce((a,b)->a+"&"+b).orElse("");
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.stripe.com/v1/checkout/sessions")).timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer "+stripeSecretKey).header("Idempotency-Key", idempotencyKey)
        .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode()/100 != 2) throw new IllegalStateException("stripe status "+response.statusCode());
    JsonNode body = json.readTree(response.body());
    return new ProviderSession(body.path("id").asText(), body.path("url").asText());
  }

  private ResponseEntity<?> recordPaymentEvent(String provider, byte[] raw) {
    try {
      JsonNode event = json.readTree(raw);
      String type = event.path("event").asText(event.path("type").asText("unknown"));
      String providerSessionId = provider.equals("razorpay")
          ? event.path("payload").path("order").path("entity").path("id").asText(event.path("payload").path("payment").path("entity").path("order_id").asText())
          : event.path("data").path("object").path("id").asText();
      String hash = Signatures.sha256(raw);
      try { db.update("insert into provider_events(provider,event_key,event_type,payload_sha256,signature_verified) values(?,?,?,?,true)", provider, hash, type, hash); }
      catch (DataIntegrityViolationException duplicate) { return ResponseEntity.ok(Map.of("received", true, "duplicate", true)); }
      if (!providerSessionId.isBlank() && Set.of("order.paid", "payment.captured", "checkout.session.completed").contains(type))
        fulfillment.recordPaidOrder(provider, providerSessionId);
      return ResponseEntity.ok(Map.of("received", true));
    } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "invalid JSON payload")); }
  }

  private boolean razorpayConfigured() { return !razorpayKeyId.isBlank() && !razorpayKeySecret.isBlank(); }
  private boolean stripeConfigured() { return !stripeSecretKey.isBlank(); }
  private boolean instagramConfigured() { return !instagramAccountId.isBlank() && !instagramAccessToken.isBlank() && !instagramAppSecret.isBlank(); }
  private Set<String> testRecipients() { Set<String> result=new HashSet<>(); for(String id:instagramTestRecipientIds.split(",")) if(!id.isBlank()) result.add(id.trim()); return result; }
  private static String optional(String value,String fallback) { return value==null || value.isBlank()?fallback:value; }
  private String toJsonOrNull(Map<String,String> value) { if (value==null || value.isEmpty()) return null; try { return json.writeValueAsString(value); } catch (Exception e) { return null; } }
  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
  private static ResponseEntity<Map<String,Object>> unavailable(String message) { return ResponseEntity.status(503).body(Map.of("error", message, "external_service", true)); }

  record CheckoutIn(long creatorId,long productId,String provider,String buyerEmail,String buyerName,Map<String,String> fieldResponses,Long slotId,Long planId) {}
  record RazorpayReturn(String orderId,String paymentId,String signature) {}
  record InstagramMessage(String recipientId,String text) {}
  record ProviderSession(String id,String redirectUrl) {}
}

final class Signatures {
  private Signatures() {}
  static boolean verifyHexHmac(String secret,String message,String provided) { return verifyHexHmac(secret,message.getBytes(StandardCharsets.UTF_8),provided); }
  static boolean verifyHexHmac(String secret,byte[] message,String provided) {
    if (secret==null || secret.isBlank() || provided==null || !provided.matches("[0-9a-fA-F]{64}")) return false;
    return MessageDigest.isEqual(hmac(secret,message).getBytes(StandardCharsets.US_ASCII),provided.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
  }
  static boolean verifyStripe(String secret,byte[] payload,String header,long nowSeconds) {
    if (header==null) return false;
    String timestamp=null; List<String> signatures=new ArrayList<>();
    for(String part:header.split(",")) { String[] pair=part.trim().split("=",2); if(pair.length==2&&pair[0].equals("t"))timestamp=pair[1]; if(pair.length==2&&pair[0].equals("v1"))signatures.add(pair[1]); }
    if(timestamp==null) return false;
    try {
      long sent=Long.parseLong(timestamp); if(Math.abs(nowSeconds-sent)>300) return false;
      byte[] signed=(timestamp+"."+new String(payload,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
      String expected=hmac(secret,signed);
      return signatures.stream().anyMatch(value -> value.matches("[0-9a-fA-F]{64}") && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),value.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII)));
    } catch(NumberFormatException ignored) { return false; }
  }
  static String sha256(byte[] payload) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)); } catch(Exception e) { throw new IllegalStateException(e); } }
  static String hmac(String secret,byte[] message) { try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(message)); } catch(Exception e) { throw new IllegalStateException(e); } }
}
