package dev.creatorstore.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
  private static final Duration SESSION_TTL = Duration.ofDays(30);

  private final JdbcTemplate db;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

  AuthController(JdbcTemplate db) { this.db = db; }

  @PostMapping("/api/auth/login")
  ResponseEntity<?> login(@RequestBody LoginIn body, HttpServletRequest request) {
    String identifier = body.handleOrEmail() == null ? "" : body.handleOrEmail().trim().toLowerCase();
    String password = body.password() == null ? "" : body.password();
    if (identifier.isBlank() || password.isBlank())
      return ResponseEntity.badRequest().body(Map.of("error", "Handle/email and password are required."));

    List<Map<String, Object>> rows = db.queryForList(
        "select id,handle,display_name,password_hash from creators where handle=? or email=?", identifier, identifier);
    if (rows.isEmpty() || !passwords.matches(password, String.valueOf(rows.get(0).get("password_hash"))))
      return ResponseEntity.status(401).body(Map.of("error", "Invalid handle/email or password."));

    long creatorId = ((Number) rows.get(0).get("id")).longValue();
    String token = SessionCookies.newToken();
    db.update("insert into sessions(id,creator_id,expires_at) values(?,?,current_timestamp + interval '30 days')",
        SessionCookies.hash(token), creatorId);

    ResponseCookie cookie = ResponseCookie.from(SessionCookies.COOKIE_NAME, token)
        .httpOnly(true).path("/").sameSite("Lax").maxAge(SESSION_TTL).secure(request.isSecure()).build();
    return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(Map.of("id", creatorId, "handle", rows.get(0).get("handle"), "displayName", rows.get(0).get("display_name")));
  }

  @PostMapping("/api/auth/logout")
  ResponseEntity<?> logout(HttpServletRequest request) {
    String token = SessionCookies.readToken(request);
    if (token != null) db.update("update sessions set revoked_at=current_timestamp where id=? and revoked_at is null", SessionCookies.hash(token));
    ResponseCookie cleared = ResponseCookie.from(SessionCookies.COOKIE_NAME, "")
        .httpOnly(true).path("/").sameSite("Lax").maxAge(0).secure(request.isSecure()).build();
    return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cleared.toString()).body(Map.of("ok", true));
  }

  @GetMapping("/api/auth/me")
  ResponseEntity<?> me(HttpServletRequest request) {
    String token = SessionCookies.readToken(request);
    if (token == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));
    List<Map<String, Object>> rows = db.queryForList(
        "select c.id,c.handle,c.display_name as \"displayName\",c.email from sessions s join creators c on c.id=s.creator_id "
            + "where s.id=? and s.revoked_at is null and s.expires_at > current_timestamp",
        SessionCookies.hash(token));
    if (rows.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));
    return ResponseEntity.ok(rows.get(0));
  }

  record LoginIn(String handleOrEmail, String password) {}
}
