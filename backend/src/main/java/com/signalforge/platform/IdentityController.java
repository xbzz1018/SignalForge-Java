package com.signalforge.platform;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IdentityController {
    private final JdbcTemplate jdbc;
    public IdentityController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @GetMapping("/api/auth/csrf") Map<String,String> csrf(CsrfToken token) {
        return Map.of("headerName",token.getHeaderName(),"parameterName",token.getParameterName(),"token",token.getToken());
    }
    @GetMapping("/api/me") Map<String,Object> me(Authentication auth) {
        return Map.of("username",auth.getName(),"projects",jdbc.queryForList("""
            SELECT p.id,p.name,m.role FROM sf_project p JOIN sf_project_member m ON m.project_id=p.id
            JOIN sf_user u ON u.id=m.user_id WHERE u.username=? AND u.enabled=true ORDER BY p.id
            """,auth.getName()));
    }
    @GetMapping("/api/health") Map<String,String> health() { jdbc.queryForObject("SELECT 1",Integer.class); return Map.of("status","UP"); }
}
