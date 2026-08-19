package dev.creatorstore.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
  private final JdbcTemplate db;

  AuthInterceptor(JdbcTemplate db) { this.db = db; }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
    String token = SessionCookies.readToken(request);
    if (token != null) {
      List<Long> rows = db.query(
          "select creator_id from sessions where id=? and revoked_at is null and expires_at > current_timestamp",
          (rs, i) -> rs.getLong("creator_id"), SessionCookies.hash(token));
      if (!rows.isEmpty()) {
        request.setAttribute("creatorId", rows.get(0));
        return true;
      }
    }
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json");
    response.getWriter().write("{\"error\":\"Authentication required.\"}");
    return false;
  }
}
