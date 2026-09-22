package dev.creatorstore;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@SpringBootApplication
public class CreatorStoreApplication {
  public static void main(String[] args) { SpringApplication.run(CreatorStoreApplication.class, args); }

  @Bean CommandLineRunner seed(JdbcTemplate db) { return args -> {
    if (db.queryForObject("select count(*) from creators", Integer.class) != 0) return;
    String disabledDemoCredential = new BCryptPasswordEncoder().encode(UUID.randomUUID().toString());
    db.update("insert into creators(handle,display_name,email,password_hash,bio) values(?,?,?,?,?)",
        "alex", "Alex Rivera", "alex@example.test", disabledDemoCredential, "Systems and templates for independent creators.");
    long creatorId = db.queryForObject("select id from creators where handle='alex'", Long.class);
    db.update("insert into stores(creator_id,title,currency,payouts_enabled) values(?,?,?,?)", creatorId, "Alex's Creator Store", "INR", false);
    db.update("insert into links(creator_id,title,url,position) values(?,?,?,?)", creatorId, "Free weekly newsletter", "https://example.com/newsletter", 1);
    db.update("insert into products(creator_id,type,title,description,price_cents,position) values(?,?,?,?,?,?)", creatorId, "digital-download", "Creator Content Calendar", "A practical 30-day content planning workbook.", 49900, 1);
    db.update("insert into products(creator_id,type,title,description,price_cents,position) values(?,?,?,?,?,?)", creatorId, "meeting", "Strategy Session", "A focused 45-minute planning call.", 249900, 2);
    long productId = db.queryForObject("select id from products where creator_id=? order by id limit 1", Long.class, creatorId);
    db.update("insert into customers(creator_id,name,email,source) values(?,?,?,?)", creatorId, "Jamie Chen", "jamie@example.test", "checkout");
    long customerId = db.queryForObject("select id from customers where creator_id=? limit 1", Long.class, creatorId);
    db.update("insert into orders(creator_id,customer_id,product_id,amount_cents,fee_cents,status,created_at) values(?,?,?,?,?,'paid',current_timestamp - interval '2 days')", creatorId, customerId, productId, 49900, 998);
    db.update("insert into leads(creator_id,product_id,email) values(?,?,?)", creatorId, productId, "reader@example.test");
    db.update("insert into store_visits(creator_id,path,referrer,occurred_at) values(?,?,?,current_timestamp - interval '1 day')", creatorId, "/alex", "instagram.com");
    for (String provider : List.of("razorpay", "stripe", "mailchimp", "zoom", "google-calendar", "instagram"))
      db.update("insert into integrations(creator_id,provider,status) values(?,?,?)", creatorId, provider, "disconnected");
    db.update("insert into notification_preferences(creator_id) values(?)", creatorId);
    db.update("insert into automations(creator_id,name,trigger_type,message,status) values(?,?,?,?,?)", creatorId, "Send free guide", "instagram_comment", "Thanks! Here is the guide: https://example.com/guide", "draft");
  }; }
}

@RestController
class CreatorController {
  private final JdbcTemplate db;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
  @Value("${app.storage-dir:./data/uploads}") String storageDir;
  CreatorController(JdbcTemplate db) { this.db = db; }

  @GetMapping("/health") Map<String,String> health() { return Map.of("status", "ok"); }

  @PostMapping("/api/public/{handle}/leads") ResponseEntity<?> captureLead(@PathVariable String handle, @RequestBody LeadIn input) {
    if (input.email()==null || !input.email().contains("@"))
      return ResponseEntity.badRequest().body(Map.of("error", "A valid email is required."));
    List<Map<String,Object>> creators = db.queryForList("select id from creators where handle=?", handle.toLowerCase());
    if (creators.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Store not found."));
    long creatorId = number(creators.get(0).get("id"));
    List<Map<String,Object>> products = db.queryForList("select id,type,fulfillment_url from products where id=? and creator_id=? and status='published'", input.productId(), creatorId);
    if (products.isEmpty() || !"lead-magnet".equals(products.get(0).get("type")))
      return ResponseEntity.status(404).body(Map.of("error", "Lead magnet not found."));
    db.update("insert into leads(creator_id,product_id,email) values(?,?,?)", creatorId, input.productId(), input.email().trim().toLowerCase());
    return ResponseEntity.status(201).body(Map.of("captured", true, "fulfillment_url", products.get(0).get("fulfillment_url")));
  }

  @GetMapping("/api/public/{handle}") ResponseEntity<?> publicPage(@PathVariable String handle) {
    List<Map<String,Object>> creators = db.queryForList("select id,handle,display_name,bio,avatar_url from creators where handle=?", handle.toLowerCase());
    if (creators.isEmpty()) return ResponseEntity.notFound().build();
    Map<String,Object> creator = creators.get(0); long id = number(creator.get("id"));
    List<Map<String,Object>> stores = db.queryForList("select title,theme,currency from stores where creator_id=? and published=true", id);
    if (stores.isEmpty()) return ResponseEntity.notFound().build();
    return ResponseEntity.ok(Map.of(
        "creator", creator,
        "store", stores.get(0),
        "links", db.queryForList("select id,title,url from links where creator_id=? and published=true order by position,id", id),
        "products", db.queryForList("select id,type,title,description,price_cents as price_subunits,price_cents,thumbnail_url from products where creator_id=? and status='published' order by position,id", id)));
  }

  @RequestMapping(value="/api/v1/authentication/check-unique-taken", method=RequestMethod.OPTIONS)
  ResponseEntity<Void> uniqueOptions() { return ResponseEntity.noContent().build(); }

  @PostMapping("/api/v1/authentication/check-unique-taken") Map<String,Object> unique(@RequestBody Map<String,Object> body) {
    String handle = text(body.get("username")).toLowerCase(); String email = text(body.get("email")).toLowerCase();
    boolean usernameTaken = !handle.isBlank() && db.queryForObject("select count(*) from creators where handle=?", Integer.class, handle) > 0;
    boolean emailTaken = !email.isBlank() && db.queryForObject("select count(*) from creators where email=?", Integer.class, email) > 0;
    return Map.of("username_taken", usernameTaken, "email_taken", emailTaken, "available", !usernameTaken && !emailTaken);
  }

  @PostMapping("/api/auth/register") ResponseEntity<?> register(@RequestBody Register r) {
    if (r.handle()==null || !r.handle().matches("[a-zA-Z0-9_]{3,40}") || r.email()==null || !r.email().contains("@") || r.password()==null || r.password().length()<8)
      return ResponseEntity.badRequest().body(Map.of("error", "Use a 3-40 character handle, valid email, and 8+ character password."));
    try {
      db.update("insert into creators(handle,display_name,email,phone,password_hash,bio) values(?,?,?,?,?,?)", r.handle().toLowerCase(), r.displayName(), r.email().toLowerCase(), r.phone(), passwords.encode(r.password()), "");
      long id = db.queryForObject("select id from creators where handle=?", Long.class, r.handle().toLowerCase());
      db.update("insert into stores(creator_id,title) values(?,?)", id, r.displayName()+"'s Store");
      db.update("insert into notification_preferences(creator_id) values(?)", id);
      return ResponseEntity.status(201).body(Map.of("id", id, "handle", r.handle().toLowerCase(), "onboarding_next", "/subscribe/socials"));
    } catch (DataIntegrityViolationException e) { return ResponseEntity.status(409).body(Map.of("error", "Handle or email already exists.")); }
  }

  @GetMapping("/api/v1/users/get_user") Map<String,Object> getUser(@RequestParam(defaultValue="alex") String handle) {
    return first("select id,handle as username,display_name,email,phone,bio,avatar_url,created_at from creators where handle=?", handle);
  }

  @PostMapping("/api/v1/users/experiments/join_communities") Map<String,Object> joinCommunities() {
    return Map.of("joined", true, "community", "creator-foundations", "joined_at", Instant.now().toString());
  }

  @GetMapping("/api/v1/users/experiments/community_stats") Map<String,Object> communityStats() {
    return Map.of("members", 1248, "online", 73, "posts_this_week", 186);
  }

  @GetMapping("/api/v1/users/experiments/metadata") Map<String,Object> experimentMetadata() {
    return Map.of("flags", Map.of("community", true, "funnels", true, "appointments", true, "email_flows", true, "autodm", true), "variant", "control");
  }

  @GetMapping("/api/v1/integrations") List<Map<String,Object>> integrations(HttpServletRequest request) {
    return db.queryForList("select id,provider,status,external_account_label from integrations where creator_id=? order by provider", creatorId(request));
  }

  @PutMapping("/api/v1/experiments/variant-assignment") Map<String,Object> variant(@RequestBody Map<String,Object> body) {
    return Map.of("experiment", text(body.get("experiment")), "variant", text(body.getOrDefault("variant", "control")), "saved", true);
  }

  @PutMapping("/api/v1/tags") ResponseEntity<?> upsertTag(@RequestBody Map<String,Object> body, HttpServletRequest request) {
    long creatorId = creatorId(request); String name = text(body.get("name")).trim();
    if (name.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
    db.update("insert into tags(creator_id,name) values(?,?) on conflict(creator_id,name) do nothing", creatorId, name);
    return ResponseEntity.ok(first("select id,name from tags where creator_id=? and name=?", creatorId, name));
  }

  @GetMapping("/api/v1/dashboard") Map<String,Object> dashboard(HttpServletRequest request) {
    long creatorId = creatorId(request);
    Map<String,Object> store = first("select title,published,payouts_enabled from stores where creator_id=?", creatorId);
    return Map.of("store", store, "metrics", metrics(creatorId), "checklist", List.of(
        Map.of("id","profile","label","Complete your profile","complete",true),
        Map.of("id","product","label","Add a product","complete",count("select count(*) from products where creator_id=?", creatorId)>0),
        Map.of("id","payouts","label","Enable payouts","complete",Boolean.TRUE.equals(store.get("payouts_enabled")))));
  }

  @GetMapping("/api/v1/store") Map<String,Object> store(HttpServletRequest request) {
    long creatorId = creatorId(request);
    return Map.of("store", first("select id,title,theme,currency,published,payouts_enabled from stores where creator_id=?", creatorId),
        "products", db.queryForList("select id,type,title,description,price_cents as price_subunits,price_cents,status,position,thumbnail_url,fulfillment_url from products where creator_id=? order by position,id", creatorId),
        "product_types", List.of("lead-magnet","digital-download","meeting","fulfillment","course","membership","webinar","community"));
  }

  @PostMapping("/api/v1/products") ResponseEntity<?> addProduct(@RequestBody ProductIn x, HttpServletRequest request) {
    long creatorId = creatorId(request);
    if (!Set.of("lead-magnet","digital-download","meeting","fulfillment","course","membership","webinar","community").contains(x.type()))
      return ResponseEntity.badRequest().body(Map.of("error", "unsupported product type"));
    if (x.title()==null || x.title().isBlank() || x.description()==null || x.priceSubunits()<0)
      return ResponseEntity.badRequest().body(Map.of("error", "title, description, and a non-negative priceSubunits are required"));
    db.update("insert into products(creator_id,type,title,description,price_cents,status,position,fulfillment_url) values(?,?,?,?,?,?,?,?)", creatorId, x.type(), x.title(), x.description(), x.priceSubunits(), x.status()==null?"draft":x.status(), x.position(), x.fulfillmentUrl());
    long id = db.queryForObject("select max(id) from products where creator_id=?", Long.class, creatorId);
    return ResponseEntity.status(201).body(first("select id,type,title,description,price_cents as price_subunits,price_cents,status,position from products where id=?", id));
  }

  @PatchMapping("/api/v1/products/{id}") ResponseEntity<?> updateProduct(@PathVariable long id, @RequestBody Map<String,Object> body, HttpServletRequest request) {
    long creatorId = creatorId(request);
    if (db.queryForList("select id from products where id=? and creator_id=?", id, creatorId).isEmpty())
      return ResponseEntity.status(404).body(Map.of("error", "Product not found."));
    List<String> sets = new ArrayList<>(); List<Object> args = new ArrayList<>();
    if (body.containsKey("title")) { sets.add("title=?"); args.add(text(body.get("title"))); }
    if (body.containsKey("description")) { sets.add("description=?"); args.add(text(body.get("description"))); }
    if (body.containsKey("priceSubunits")) { sets.add("price_cents=?"); args.add((int) longValue(body.get("priceSubunits"))); }
    if (body.containsKey("status")) {
      String status = text(body.get("status"));
      if (!Set.of("draft","published","archived").contains(status))
        return ResponseEntity.badRequest().body(Map.of("error", "status must be draft, published, or archived"));
      sets.add("status=?"); args.add(status);
    }
    if (body.containsKey("fulfillmentUrl")) { sets.add("fulfillment_url=?"); args.add(text(body.get("fulfillmentUrl"))); }
    if (sets.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No editable fields were supplied."));
    args.add(id); args.add(creatorId);
    db.update("update products set "+String.join(",", sets)+" where id=? and creator_id=?", args.toArray());
    return ResponseEntity.ok(first("select id,type,title,description,price_cents as price_subunits,price_cents,status,position,thumbnail_url,fulfillment_url from products where id=?", id));
  }

  @GetMapping("/api/buyer/access/{token}") ResponseEntity<?> buyerAccess(@PathVariable String token) {
    List<Map<String,Object>> rows = db.queryForList(
        "select e.status as entitlement_status,e.granted_at,p.id as product_id,p.type,p.title,p.description,p.thumbnail_url,p.fulfillment_url,"
            + "o.amount_cents as amount_subunits,o.created_at as purchased_at,c.display_name as creator_name,c.handle as creator_handle "
            + "from entitlements e join products p on p.id=e.product_id join orders o on o.id=e.order_id join creators c on c.id=e.creator_id "
            + "where e.access_token=?", token);
    if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Access link not found."));
    Map<String,Object> result = new LinkedHashMap<>(rows.get(0));
    if ("digital-download".equals(result.get("type")))
      result.put("files", db.queryForList("select id,file_name from product_files where product_id=? order by id", result.get("product_id")));
    if ("meeting".equals(result.get("type"))) {
      List<Map<String,Object>> booking = db.queryForList(
          "select b.starts_at,b.ends_at from bookings b join checkout_sessions cs on cs.slot_id=b.id "
              + "join entitlements e on e.order_id=cs.order_id where e.access_token=?", token);
      if (!booking.isEmpty()) result.put("booking", booking.get(0));
    }
    if ("webinar".equals(result.get("type"))) {
      List<Map<String,Object>> reg = db.queryForList(
          "select w.starts_at,w.ends_at,w.join_url from webinar_registrations r join webinar_sessions w on w.id=r.session_id "
              + "where r.order_id=(select order_id from entitlements where access_token=?)", token);
      if (!reg.isEmpty()) result.put("webinar_session", reg.get(0));
    }
    if ("membership".equals(result.get("type"))) {
      List<Map<String,Object>> sub = db.queryForList(
          "select ms.current_period_end,(ms.current_period_end > current_timestamp) as active from membership_subscriptions ms "
              + "join entitlements e on e.order_id=ms.order_id where e.access_token=?", token);
      if (!sub.isEmpty()) result.put("membership", sub.get(0));
    }
    return ResponseEntity.ok(result);
  }

  @PostMapping("/api/v1/products/{id}/files") ResponseEntity<?> uploadProductFile(@PathVariable long id, @RequestParam("file") MultipartFile file, HttpServletRequest request) throws IOException {
    long creatorId = creatorId(request);
    if (db.queryForList("select id from products where id=? and creator_id=?", id, creatorId).isEmpty())
      return ResponseEntity.status(404).body(Map.of("error", "Product not found."));
    if (file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "A file is required."));
    if (file.getSize() > 50L * 1024 * 1024) return ResponseEntity.badRequest().body(Map.of("error", "File must be 50MB or smaller."));
    String safeName = Optional.ofNullable(file.getOriginalFilename()).orElse("file").replaceAll("[^a-zA-Z0-9._-]", "_");
    String objectKey = UUID.randomUUID()+"_"+safeName;
    Path dir = Path.of(storageDir).toAbsolutePath().normalize();
    Files.createDirectories(dir);
    Path dest = dir.resolve(objectKey).normalize();
    if (!dest.startsWith(dir)) return ResponseEntity.badRequest().body(Map.of("error", "Invalid file name."));
    file.transferTo(dest);
    db.update("insert into product_files(product_id,file_name,object_key) values(?,?,?)", id, safeName, objectKey);
    long fileId = db.queryForObject("select max(id) from product_files where product_id=?", Long.class, id);
    return ResponseEntity.status(201).body(Map.of("id", fileId, "file_name", safeName));
  }

  @PostMapping("/api/v1/products/{id}/thumbnail") ResponseEntity<?> uploadThumbnail(@PathVariable long id, @RequestParam("file") MultipartFile file, HttpServletRequest request) throws IOException {
    long creatorId = creatorId(request);
    if (db.queryForList("select id from products where id=? and creator_id=?", id, creatorId).isEmpty())
      return ResponseEntity.status(404).body(Map.of("error", "Product not found."));
    if (file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "An image is required."));
    if (file.getSize() > 8L * 1024 * 1024) return ResponseEntity.badRequest().body(Map.of("error", "Image must be 8MB or smaller."));
    String contentType = file.getContentType();
    if (contentType == null || !contentType.startsWith("image/"))
      return ResponseEntity.badRequest().body(Map.of("error", "File must be an image."));
    String ext = switch (contentType) { case "image/png" -> ".png"; case "image/webp" -> ".webp"; case "image/gif" -> ".gif"; default -> ".jpg"; };
    String objectKey = "thumb_"+UUID.randomUUID()+ext;
    Path dir = Path.of(storageDir).toAbsolutePath().normalize().resolve("thumbnails");
    Files.createDirectories(dir);
    Path dest = dir.resolve(objectKey).normalize();
    if (!dest.startsWith(dir)) return ResponseEntity.badRequest().body(Map.of("error", "Invalid file name."));
    file.transferTo(dest);
    String url = "/api/public/thumbnails/"+objectKey;
    db.update("update products set thumbnail_url=? where id=?", url, id);
    return ResponseEntity.ok(Map.of("thumbnail_url", url));
  }

  @GetMapping("/api/public/thumbnails/{key}") ResponseEntity<?> serveThumbnail(@PathVariable String key) throws IOException {
    Path dir = Path.of(storageDir).toAbsolutePath().normalize().resolve("thumbnails");
    Path path = dir.resolve(key).normalize();
    if (!path.startsWith(dir) || !Files.exists(path)) return ResponseEntity.notFound().build();
    byte[] bytes = Files.readAllBytes(path);
    String contentType = Files.probeContentType(path);
    return ResponseEntity.ok().contentType(contentType!=null?MediaType.parseMediaType(contentType):MediaType.APPLICATION_OCTET_STREAM).body(bytes);
  }

  @GetMapping("/api/v1/products/{id}/files") List<Map<String,Object>> listProductFiles(@PathVariable long id, HttpServletRequest request) {
    long creatorId = creatorId(request);
    if (db.queryForList("select id from products where id=? and creator_id=?", id, creatorId).isEmpty()) return List.of();
    return db.queryForList("select id,file_name from product_files where product_id=? order by id", id);
  }

  @PostMapping("/api/v1/products/{id}/fields") ResponseEntity<?> addProductField(@PathVariable long id, @RequestBody FieldIn x, HttpServletRequest request) {
    long creatorId = creatorId(request);
    if (db.queryForList("select id from products where id=? and creator_id=?", id, creatorId).isEmpty())
      return ResponseEntity.status(404).body(Map.of("error", "Product not found."));
    if (x.label()==null || x.label().isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "label is required"));
    db.update("insert into product_checkout_fields(product_id,label,field_type,required,position) values(?,?,?,?,?)",
        id, x.label().trim(), x.fieldType()==null||x.fieldType().isBlank()?"text":x.fieldType(), x.required(),
        count("select count(*) from product_checkout_fields where product_id=?", id));
    return ResponseEntity.status(201).body(Map.of("added", true));
  }

  @GetMapping("/api/v1/products/{id}/fields") List<Map<String,Object>> listProductFields(@PathVariable long id, HttpServletRequest request) {
    long creatorId = creatorId(request);
    if (db.queryForList("select id from products where id=? and creator_id=?", id, creatorId).isEmpty()) return List.of();
    return db.queryForList("select id,label,field_type,required from product_checkout_fields where product_id=? order by position,id", id);
  }

  @GetMapping("/api/public/products/{id}/fields") List<Map<String,Object>> publicProductFields(@PathVariable long id) {
    return db.queryForList("select id,label,field_type,required from product_checkout_fields where product_id=? order by position,id", id);
  }

  @GetMapping("/api/v1/orders/{id}/fields") List<Map<String,Object>> orderFieldResponses(@PathVariable long id, HttpServletRequest request) {
    long creatorId = creatorId(request);
    return db.queryForList("select f.label,r.value from order_field_responses r join product_checkout_fields f on f.id=r.field_id "
        + "join orders o on o.id=r.order_id where r.order_id=? and o.creator_id=?", id, creatorId);
  }

  @PostMapping("/api/v1/products/{id}/slots") ResponseEntity<?> addSlot(@PathVariable long id, @RequestBody SlotIn x, HttpServletRequest request) {
    long creatorId = creatorId(request);
    if (db.queryForList("select id from products where id=? and creator_id=? and type='meeting'", id, creatorId).isEmpty())
      return ResponseEntity.status(404).body(Map.of("error", "Meeting product not found."));
    java.sql.Timestamp startsAt, endsAt;
    try { startsAt = java.sql.Timestamp.from(Instant.parse(x.startsAt())); endsAt = java.sql.Timestamp.from(Instant.parse(x.endsAt())); }
    catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "startsAt/endsAt must be ISO-8601 timestamps.")); }
    if (!endsAt.after(startsAt)) return ResponseEntity.badRequest().body(Map.of("error", "endsAt must be after startsAt."));
    long scheduleId = getOrCreateSchedule(creatorId);
    db.update("insert into bookings(schedule_id,product_id,starts_at,ends_at,status) values(?,?,?,?,'open')", scheduleId, id, startsAt, endsAt);
    return ResponseEntity.status(201).body(Map.of("added", true));
  }

  @GetMapping("/api/v1/products/{id}/slots") List<Map<String,Object>> listSlotsForCreator(@PathVariable long id, HttpServletRequest request) {
    long creatorId = creatorId(request);
    return db.queryForList("select b.id,b.starts_at,b.ends_at,b.status from bookings b join availability_schedules s on s.id=b.schedule_id "
        + "where s.creator_id=? and b.product_id=? order by b.starts_at", creatorId, id);
  }

  @GetMapping("/api/public/products/{id}/slots") List<Map<String,Object>> listOpenSlots(@PathVariable long id) {
    return db.queryForList("select id,starts_at,ends_at from bookings where product_id=? and status='open' and starts_at > current_timestamp order by starts_at limit 50", id);
  }

  private long getOrCreateSchedule(long creatorId) {
    List<Long> existing = db.query("select id from availability_schedules where creator_id=? order by id limit 1", (rs,i) -> rs.getLong("id"), creatorId);
    if (!existing.isEmpty()) return existing.get(0);
    db.update("insert into availability_schedules(creator_id,name,timezone) values(?,?,?)", creatorId, "Meetings", "UTC");
    return db.queryForObject("select id from availability_schedules where creator_id=? order by id desc limit 1", Long.class, creatorId);
  }

  @PostMapping("/api/v1/products/{id}/webinar-sessions") ResponseEntity<?> addWebinarSession(@PathVariable long id, @RequestBody WebinarSessionIn x, HttpServletRequest request) {
    long creatorId = creatorId(request);
    if (db.queryForList("select id from products where id=? and creator_id=? and type='webinar'", id, creatorId).isEmpty())
      return ResponseEntity.status(404).body(Map.of("error", "Webinar product not found."));
    if (x.joinUrl()==null || x.joinUrl().isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "joinUrl is required."));
    java.sql.Timestamp startsAt, endsAt;
    try { startsAt = java.sql.Timestamp.from(Instant.parse(x.startsAt())); endsAt = java.sql.Timestamp.from(Instant.parse(x.endsAt())); }
    catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "startsAt/endsAt must be ISO-8601 timestamps.")); }
    db.update("insert into webinar_sessions(product_id,starts_at,ends_at,join_url,capacity) values(?,?,?,?,?)", id, startsAt, endsAt, x.joinUrl(), x.capacity());
    return ResponseEntity.status(201).body(Map.of("added", true));
  }

  @GetMapping("/api/v1/products/{id}/webinar-sessions") List<Map<String,Object>> listWebinarSessionsForCreator(@PathVariable long id, HttpServletRequest request) {
    long creatorId = creatorId(request);
    if (db.queryForList("select id from products where id=? and creator_id=?", id, creatorId).isEmpty()) return List.of();
    return db.queryForList("select w.id,w.starts_at,w.ends_at,w.join_url,w.capacity,"
        + "(select count(*) from webinar_registrations r where r.session_id=w.id) as registered "
        + "from webinar_sessions w where w.product_id=? order by w.starts_at", id);
  }

  @PostMapping("/api/v1/products/{id}/plans") ResponseEntity<?> addPlan(@PathVariable long id, @RequestBody PlanIn x, HttpServletRequest request) {
    long creatorId = creatorId(request);
    if (db.queryForList("select id from products where id=? and creator_id=? and type='membership'", id, creatorId).isEmpty())
      return ResponseEntity.status(404).body(Map.of("error", "Membership product not found."));
    if (x.name()==null || x.name().isBlank() || x.amountSubunits()<=0)
      return ResponseEntity.badRequest().body(Map.of("error", "name and a positive amountSubunits are required"));
    if (!Set.of("week","month","year").contains(x.intervalName()))
      return ResponseEntity.badRequest().body(Map.of("error", "intervalName must be week, month, or year"));
    db.update("insert into product_payment_plans(product_id,name,amount_cents,interval_name,interval_count) values(?,?,?,?,?)",
        id, x.name(), x.amountSubunits(), x.intervalName(), Math.max(1, x.intervalCount()));
    return ResponseEntity.status(201).body(Map.of("added", true));
  }

  @GetMapping("/api/v1/products/{id}/plans") List<Map<String,Object>> listPlansForCreator(@PathVariable long id, HttpServletRequest request) {
    long creatorId = creatorId(request);
    if (db.queryForList("select id from products where id=? and creator_id=?", id, creatorId).isEmpty()) return List.of();
    return db.queryForList("select id,name,amount_cents as amount_subunits,interval_name,interval_count from product_payment_plans where product_id=? order by id", id);
  }

  @GetMapping("/api/public/products/{id}/plans") List<Map<String,Object>> publicPlans(@PathVariable long id) {
    return db.queryForList("select id,name,amount_cents as amount_subunits,interval_name,interval_count from product_payment_plans where product_id=? order by id", id);
  }

  @PostMapping("/api/v1/products/{id}/modules") ResponseEntity<?> addModule(@PathVariable long id, @RequestBody ModuleIn x, HttpServletRequest request) {
    long creatorId = creatorId(request);
    if (db.queryForList("select id from products where id=? and creator_id=? and type='course'", id, creatorId).isEmpty())
      return ResponseEntity.status(404).body(Map.of("error", "Course product not found."));
    if (x.title()==null || x.title().isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "title is required"));
    db.update("insert into course_modules(product_id,title,position) values(?,?,?)", id, x.title().trim(),
        count("select count(*) from course_modules where product_id=?", id));
    return ResponseEntity.status(201).body(Map.of("added", true));
  }

  @GetMapping("/api/v1/products/{id}/modules") List<Map<String,Object>> listModulesForCreator(@PathVariable long id, HttpServletRequest request) {
    long creatorId = creatorId(request);
    if (db.queryForList("select id from products where id=? and creator_id=?", id, creatorId).isEmpty()) return List.of();
    return courseModules(id);
  }

  @PostMapping("/api/v1/modules/{moduleId}/lessons") ResponseEntity<?> addLesson(@PathVariable long moduleId, @RequestBody LessonIn x, HttpServletRequest request) {
    long creatorId = creatorId(request);
    if (db.queryForList("select m.id from course_modules m join products p on p.id=m.product_id where m.id=? and p.creator_id=?", moduleId, creatorId).isEmpty())
      return ResponseEntity.status(404).body(Map.of("error", "Module not found."));
    if (x.title()==null || x.title().isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "title is required"));
    db.update("insert into course_lessons(module_id,title,video_url,content,position) values(?,?,?,?,?)",
        moduleId, x.title().trim(), x.videoUrl(), x.content(), count("select count(*) from course_lessons where module_id=?", moduleId));
    return ResponseEntity.status(201).body(Map.of("added", true));
  }

  @GetMapping("/api/public/products/{id}/curriculum") List<Map<String,Object>> publicCurriculum(@PathVariable long id) {
    List<Map<String,Object>> modules = db.queryForList("select id,title,position from course_modules where product_id=? order by position,id", id);
    for (var m : modules) m.put("lessons", db.queryForList("select id,title,position from course_lessons where module_id=? order by position,id", m.get("id")));
    return modules;
  }

  private List<Map<String,Object>> courseModules(long productId) {
    List<Map<String,Object>> modules = db.queryForList("select id,title,position from course_modules where product_id=? order by position,id", productId);
    for (var m : modules) m.put("lessons", db.queryForList("select id,title,video_url,content,position from course_lessons where module_id=? order by position,id", m.get("id")));
    return modules;
  }

  @GetMapping("/api/buyer/access/{token}/curriculum") ResponseEntity<?> buyerCurriculum(@PathVariable String token) {
    List<Map<String,Object>> ent = db.queryForList(
        "select e.product_id,ce.id as enrollment_id from entitlements e join course_enrollments ce on ce.order_id=e.order_id where e.access_token=?", token);
    if (ent.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Not enrolled."));
    long productId = number(ent.get(0).get("product_id"));
    long enrollmentId = number(ent.get(0).get("enrollment_id"));
    List<Map<String,Object>> modules = courseModules(productId);
    for (var m : modules) {
      @SuppressWarnings("unchecked") List<Map<String,Object>> lessons = (List<Map<String,Object>>) m.get("lessons");
      for (var l : lessons) l.put("completed", count("select count(*) from lesson_progress where enrollment_id=? and lesson_id=?", enrollmentId, l.get("id")) > 0);
    }
    return ResponseEntity.ok(Map.of("modules", modules));
  }

  @PostMapping("/api/buyer/access/{token}/lessons/{lessonId}/complete") ResponseEntity<?> completeLesson(@PathVariable String token, @PathVariable long lessonId) {
    List<Long> enrollmentRows = db.query(
        "select ce.id from course_enrollments ce join entitlements e on e.order_id=ce.order_id where e.access_token=?",
        (rs, i) -> rs.getLong("id"), token);
    if (enrollmentRows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Not enrolled."));
    try { db.update("insert into lesson_progress(enrollment_id,lesson_id) values(?,?)", enrollmentRows.get(0), lessonId); }
    catch (DataIntegrityViolationException alreadyDone) { /* already marked complete */ }
    return ResponseEntity.ok(Map.of("completed", true));
  }

  @GetMapping("/api/buyer/access/{token}/download/{fileId}") ResponseEntity<?> buyerDownload(@PathVariable String token, @PathVariable long fileId) throws IOException {
    List<Map<String,Object>> rows = db.queryForList(
        "select f.file_name,f.object_key from entitlements e join product_files f on f.product_id=e.product_id "
            + "where e.access_token=? and e.status='active' and f.id=?", token, fileId);
    if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "File not found or access expired."));
    Map<String,Object> f = rows.get(0);
    Path path = Path.of(storageDir).toAbsolutePath().normalize().resolve(String.valueOf(f.get("object_key")));
    if (!Files.exists(path)) return ResponseEntity.status(404).body(Map.of("error", "File missing on server."));
    byte[] bytes = Files.readAllBytes(path);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\""+f.get("file_name")+"\"")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(bytes);
  }

  @GetMapping("/api/v1/income/export.csv") ResponseEntity<String> exportIncomeCsv(HttpServletRequest request) {
    long creatorId = creatorId(request);
    List<Map<String,Object>> orders = db.queryForList(
        "select o.id,o.created_at,c.name as customer,c.email as customer_email,p.title as product,o.amount_cents,o.fee_cents,o.status "
            + "from orders o left join customers c on c.id=o.customer_id left join products p on p.id=o.product_id "
            + "where o.creator_id=? order by o.created_at desc", creatorId);
    StringBuilder sb = new StringBuilder("Order ID,Date,Customer,Email,Product,Amount (paise),Fee (paise),Status\n");
    for (var o : orders)
      sb.append(o.get("id")).append(',').append(o.get("created_at")).append(',').append(csvEscape(text(o.get("customer"))))
          .append(',').append(csvEscape(text(o.get("customer_email")))).append(',').append(csvEscape(text(o.get("product"))))
          .append(',').append(o.get("amount_cents")).append(',').append(o.get("fee_cents")).append(',').append(o.get("status")).append('\n');
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"income.csv\"")
        .contentType(MediaType.parseMediaType("text/csv")).body(sb.toString());
  }
  private static String csvEscape(String s) { return s.contains(",")||s.contains("\"") ? "\""+s.replace("\"","\"\"")+"\"" : s; }

  @PostMapping("/api/v1/customers/import") ResponseEntity<?> importCustomers(@RequestParam("file") MultipartFile file, HttpServletRequest request) throws IOException {
    long creatorId = creatorId(request);
    int imported = 0, skipped = 0;
    try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(file.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
      String line; boolean first = true;
      while ((line = reader.readLine()) != null) {
        if (first) { first = false; continue; }
        if (line.isBlank()) continue;
        String[] parts = line.split(",", -1);
        if (parts.length < 2) { skipped++; continue; }
        String name = parts[0].trim(); String email = parts[1].trim().toLowerCase();
        if (email.isBlank() || !email.contains("@")) { skipped++; continue; }
        String phone = parts.length > 2 && !parts[2].isBlank() ? parts[2].trim() : null;
        try {
          db.update("insert into customers(creator_id,name,email,phone,source) values(?,?,?,?,'import')", creatorId, name.isBlank()?email:name, email, phone);
          imported++;
        } catch (DataIntegrityViolationException duplicate) { skipped++; }
      }
    }
    return ResponseEntity.ok(Map.of("imported", imported, "skipped", skipped));
  }

  @PostMapping("/api/v1/landing-pages") ResponseEntity<?> addLandingPage(@RequestBody LandingPageIn x, HttpServletRequest request) {
    long creatorId = creatorId(request);
    String slug = text(x.slug()).trim().toLowerCase().replaceAll("[^a-z0-9-]", "-");
    if (slug.isBlank() || x.title()==null || x.title().isBlank())
      return ResponseEntity.badRequest().body(Map.of("error", "slug and title are required"));
    try {
      db.update("insert into landing_pages(creator_id,slug,title,headline,body) values(?,?,?,?,?)",
          creatorId, slug, x.title(), x.headline()==null?"":x.headline(), x.body()==null?"":x.body());
    } catch (DataIntegrityViolationException duplicate) {
      return ResponseEntity.status(409).body(Map.of("error", "You already have a landing page with that slug."));
    }
    return ResponseEntity.status(201).body(Map.of("added", true, "slug", slug));
  }

  @GetMapping("/api/v1/landing-pages") List<Map<String,Object>> listLandingPages(HttpServletRequest request) {
    long creatorId = creatorId(request);
    List<Map<String,Object>> pages = db.queryForList(
        "select id,slug,title,headline,body,published,created_at from landing_pages where creator_id=? order by created_at desc", creatorId);
    for (var p : pages) p.put("products", db.queryForList(
        "select p.id,p.title from landing_page_products lp join products p on p.id=lp.product_id where lp.landing_page_id=? order by lp.position", p.get("id")));
    return pages;
  }

  @PatchMapping("/api/v1/landing-pages/{id}") ResponseEntity<?> updateLandingPage(@PathVariable long id, @RequestBody Map<String,Object> body, HttpServletRequest request) {
    long creatorId = creatorId(request);
    if (db.queryForList("select id from landing_pages where id=? and creator_id=?", id, creatorId).isEmpty())
      return ResponseEntity.status(404).body(Map.of("error", "Landing page not found."));
    List<String> sets = new ArrayList<>(); List<Object> args = new ArrayList<>();
    if (body.containsKey("title")) { sets.add("title=?"); args.add(text(body.get("title"))); }
    if (body.containsKey("headline")) { sets.add("headline=?"); args.add(text(body.get("headline"))); }
    if (body.containsKey("body")) { sets.add("body=?"); args.add(text(body.get("body"))); }
    if (body.containsKey("published")) { sets.add("published=?"); args.add(Boolean.TRUE.equals(body.get("published"))); }
    if (sets.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No editable fields were supplied."));
    args.add(id); args.add(creatorId);
    db.update("update landing_pages set "+String.join(",", sets)+" where id=? and creator_id=?", args.toArray());
    return ResponseEntity.ok(Map.of("saved", true));
  }

  @PostMapping("/api/v1/landing-pages/{id}/products") ResponseEntity<?> addLandingPageProduct(@PathVariable long id, @RequestBody Map<String,Object> body, HttpServletRequest request) {
    long creatorId = creatorId(request);
    if (db.queryForList("select id from landing_pages where id=? and creator_id=?", id, creatorId).isEmpty())
      return ResponseEntity.status(404).body(Map.of("error", "Landing page not found."));
    long productId = longValue(body.get("productId"));
    if (db.queryForList("select id from products where id=? and creator_id=?", productId, creatorId).isEmpty())
      return ResponseEntity.badRequest().body(Map.of("error", "Product not found."));
    db.update("insert into landing_page_products(landing_page_id,product_id,position) values(?,?,?) on conflict do nothing",
        id, productId, count("select count(*) from landing_page_products where landing_page_id=?", id));
    return ResponseEntity.status(201).body(Map.of("added", true));
  }

  @GetMapping("/api/public/{handle}/p/{slug}") ResponseEntity<?> publicLandingPage(@PathVariable String handle, @PathVariable String slug) {
    List<Map<String,Object>> creators = db.queryForList("select id,handle,display_name,bio from creators where handle=?", handle.toLowerCase());
    if (creators.isEmpty()) return ResponseEntity.notFound().build();
    long creatorId = number(creators.get(0).get("id"));
    List<Map<String,Object>> pages = db.queryForList(
        "select id,title,headline,body from landing_pages where creator_id=? and slug=? and published=true", creatorId, slug.toLowerCase());
    if (pages.isEmpty()) return ResponseEntity.notFound().build();
    Map<String,Object> page = pages.get(0);
    List<Map<String,Object>> products = db.queryForList(
        "select p.id,p.type,p.title,p.description,p.price_cents as price_subunits,p.thumbnail_url,s.currency "
            + "from landing_page_products lp join products p on p.id=lp.product_id join stores s on s.creator_id=p.creator_id "
            + "where lp.landing_page_id=? and p.status='published' order by lp.position", page.get("id"));
    return ResponseEntity.ok(Map.of("creator", creators.get(0), "page", page, "products", products));
  }

  @PatchMapping("/api/v1/settings/store") ResponseEntity<?> updateStoreDesign(@RequestBody Map<String,Object> body, HttpServletRequest request) {
    long creatorId = creatorId(request);
    if (!body.containsKey("theme")) return ResponseEntity.badRequest().body(Map.of("error", "No editable fields were supplied."));
    String theme = text(body.get("theme"));
    if (!Set.of("bold","minimal","sunset","ocean","botanical","editorial").contains(theme))
      return ResponseEntity.badRequest().body(Map.of("error", "Unsupported theme."));
    db.update("update stores set theme=? where creator_id=?", theme, creatorId);
    return ResponseEntity.ok(Map.of("saved", true));
  }

  @PatchMapping("/api/v1/settings/profile") ResponseEntity<?> updateProfile(@RequestBody Map<String,Object> body, HttpServletRequest request) {
    long creatorId = creatorId(request);
    List<String> sets = new ArrayList<>(); List<Object> args = new ArrayList<>();
    if (body.containsKey("displayName")) { sets.add("display_name=?"); args.add(text(body.get("displayName"))); }
    if (body.containsKey("bio")) { sets.add("bio=?"); args.add(text(body.get("bio"))); }
    if (sets.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No editable fields were supplied."));
    args.add(creatorId);
    db.update("update creators set "+String.join(",", sets)+" where id=?", args.toArray());
    return ResponseEntity.ok(Map.of("saved", true));
  }

  @GetMapping("/api/v1/income") Map<String,Object> income(HttpServletRequest request) {
    long creatorId = creatorId(request);
    String currency = String.valueOf(first("select currency from stores where creator_id=?", creatorId).getOrDefault("currency", "INR"));
    return Map.of("summary", first("select coalesce(sum(amount_cents),0) as gross_subunits,coalesce(sum(fee_cents),0) as fees_subunits,coalesce(sum(amount_cents-fee_cents),0) as net_subunits,count(*) as order_count from orders where creator_id=? and status='paid'", creatorId),
        "orders", db.queryForList("select o.id,o.amount_cents as amount_subunits,o.fee_cents as fee_subunits,o.status,o.created_at,c.name as customer,p.title as product from orders o left join customers c on c.id=o.customer_id left join products p on p.id=o.product_id where o.creator_id=? order by o.created_at desc limit 100", creatorId),
        "currency", currency, "cashout", Map.of("available_subunits", 48902, "pending_subunits", 0),
        "real_money_notice", "Amounts represent real-world currency in the smallest unit. No payout occurs unless a configured provider confirms it.");
  }

  @GetMapping("/api/v1/analytics") Map<String,Object> analytics(HttpServletRequest request) {
    long creatorId = creatorId(request);
    return Map.of("totals", metrics(creatorId), "sources", db.queryForList("select coalesce(referrer,'direct') as source,count(*) as visits from store_visits where creator_id=? group by coalesce(referrer,'direct') order by visits desc", creatorId));
  }

  @GetMapping("/api/v1/customers") Map<String,Object> customers(HttpServletRequest request) {
    return Map.of("items", db.queryForList("select id,name,email,phone,source,created_at from customers where creator_id=? order by created_at desc limit 5000", creatorId(request)), "limit", 5000);
  }

  @PostMapping("/api/v1/customers") ResponseEntity<?> addCustomer(@RequestBody CustomerIn x, HttpServletRequest request) {
    long creatorId = creatorId(request);
    try { db.update("insert into customers(creator_id,name,email,phone,source) values(?,?,?,?,?)", creatorId,x.name(),x.email().toLowerCase(),x.phone(),"manual");
      return ResponseEntity.status(201).body(first("select id,name,email,phone,source,created_at from customers where creator_id=? and email=?", creatorId,x.email().toLowerCase()));
    } catch (DataIntegrityViolationException e) { return ResponseEntity.status(409).body(Map.of("error","customer already exists")); }
  }

  @GetMapping("/api/v1/success") Map<String,Object> success() {
    return Map.of("tutorials", List.of(
        Map.of("id","launch","title","Launch your first offer","minutes",8,"category","Getting started"),
        Map.of("id","audience","title","Turn an audience into customers","minutes",12,"category","Growth"),
        Map.of("id","pricing","title","Price a digital product","minutes",10,"category","Sales")));
  }

  @GetMapping("/api/v1/more") Map<String,Object> more(HttpServletRequest request) {
    long creatorId = creatorId(request);
    return Map.of("funnels", db.queryForList("select id,name,status from funnels where creator_id=? order by id", creatorId),
        "appointments", db.queryForList("select b.id,b.starts_at,b.ends_at,b.status from bookings b join availability_schedules s on s.id=b.schedule_id where s.creator_id=? order by b.starts_at", creatorId),
        "features", List.of("funnels","appointments","referrals","email-flows","autodm"));
  }

  @GetMapping("/api/v1/settings") Map<String,Object> settings(HttpServletRequest request) {
    long creatorId = creatorId(request);
    return Map.of("profile", first("select id,handle as username,display_name,email,phone,bio,avatar_url from creators where id=?", creatorId),
        "store", first("select title,theme,currency,published,payouts_enabled from stores where creator_id=?", creatorId),
        "notifications", first("select order_emails,marketing_emails,payout_emails from notification_preferences where creator_id=?", creatorId),
        "tabs", List.of("profile","integrations","billing","payments","email-notifications","security"));
  }

  @GetMapping("/api/v1/automations/instagram-posts-metadata") Map<String,Object> instagramMetadata(HttpServletRequest request) {
    return Map.of("connected", false, "posts", List.of(), "creator_id", creatorId(request));
  }

  @GetMapping("/api/v1/automations/analytics") Map<String,Object> automationAnalytics(@RequestParam(name="automation_ids", required=false) List<Long> ids, HttpServletRequest request) {
    long creatorId = creatorId(request);
    if (ids==null || ids.isEmpty()) return Map.of("items", List.of());
    List<Map<String,Object>> items = new ArrayList<>();
    for (Long id : ids) {
      List<Map<String,Object>> rows = db.queryForList(
          "select s.automation_id,s.comments_seen,s.messages_sent,s.link_clicks,s.updated_at from automation_stats s join automations a on a.id=s.automation_id where s.automation_id=? and a.creator_id=?", id, creatorId);
      if (rows.isEmpty()) items.add(Map.of("automation_id", id, "comments_seen", 0, "messages_sent", 0, "link_clicks", 0));
      else items.add(rows.get(0));
    }
    return Map.of("items", items);
  }

  @PostMapping("/events") ResponseEntity<?> analyticsEvent(@RequestBody Map<String,Object> event) {
    long creatorId = longValue(event.getOrDefault("creator_id", 1)); String name = text(event.getOrDefault("event", "page_view"));
    if (name.equals("page_view")) db.update("insert into store_visits(creator_id,path,referrer) values(?,?,?)", creatorId, text(event.getOrDefault("path", "/")), text(event.get("referrer")));
    return ResponseEntity.accepted().body(Map.of("accepted", true));
  }

  @PostMapping("/api/events/click") ResponseEntity<?> click(@RequestBody ClickIn x) {
    db.update("insert into click_events(link_id,referrer) values(?,?)", x.linkId(), x.referrer()); return ResponseEntity.accepted().build();
  }

  private Map<String,Object> metrics(long creatorId) {
    return Map.of("visits", count("select count(*) from store_visits where creator_id=?", creatorId),
        "leads", count("select count(*) from leads where creator_id=?", creatorId),
        "orders", count("select count(*) from orders where creator_id=? and status='paid'", creatorId),
        "revenue_subunits", count("select coalesce(sum(amount_cents),0) from orders where creator_id=? and status='paid'", creatorId));
  }
  private Map<String,Object> first(String sql, Object... args) { List<Map<String,Object>> rows=db.queryForList(sql,args); return rows.isEmpty()?Map.of():rows.get(0); }
  private long count(String sql,Object... args) { Number n=db.queryForObject(sql,Number.class,args); return n==null?0:n.longValue(); }
  private static long number(Object x) { return ((Number)x).longValue(); }
  private static long longValue(Object x) { return x instanceof Number n?n.longValue():Long.parseLong(text(x)); }
  private static String text(Object x) { return x==null?"":String.valueOf(x); }
  private static long creatorId(HttpServletRequest request) { return (Long) request.getAttribute("creatorId"); }

  record Register(String handle,String displayName,String email,String phone,String password) {}
  record ProductIn(String type,String title,String description,int priceSubunits,String status,int position,String fulfillmentUrl) {}
  record CustomerIn(String name,String email,String phone) {}
  record ClickIn(long linkId,String referrer) {}
  record LeadIn(long productId,String email) {}
  record FieldIn(String label,String fieldType,boolean required) {}
  record SlotIn(String startsAt,String endsAt) {}
  record WebinarSessionIn(String startsAt,String endsAt,String joinUrl,int capacity) {}
  record PlanIn(String name,int amountSubunits,String intervalName,int intervalCount) {}
  record ModuleIn(String title) {}
  record LessonIn(String title,String videoUrl,String content) {}
  record LandingPageIn(String slug,String title,String headline,String body) {}
}
