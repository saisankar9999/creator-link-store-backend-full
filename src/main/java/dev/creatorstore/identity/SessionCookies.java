package dev.creatorstore.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

class SessionCookies {
  static final String COOKIE_NAME = "cs_session";
  private static final SecureRandom RANDOM = new SecureRandom();

  static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String hash(String token) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) { throw new RuntimeException(e); }
  }

  static String readToken(HttpServletRequest request) {
    if (request.getCookies() == null) return null;
    for (var c : request.getCookies()) if (COOKIE_NAME.equals(c.getName())) return c.getValue();
    return null;
  }
}
