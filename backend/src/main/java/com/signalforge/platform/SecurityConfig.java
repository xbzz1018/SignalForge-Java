package com.signalforge.platform;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(); }
    @Bean UserDetailsService users(JdbcTemplate jdbc) {
        return name -> jdbc.query("SELECT username,password_hash,enabled FROM sf_user WHERE username=?",
                (rs,n) -> User.withUsername(rs.getString(1)).password(rs.getString(2))
                        .disabled(!rs.getBoolean(3)).roles("USER").build(),name).stream().findFirst()
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a.requestMatchers("/","/index.html","/workspace.js","/workspace.css","/api/auth/csrf","/api/health").permitAll()
                    .anyRequest().authenticated())
            .formLogin(f -> f.loginProcessingUrl("/api/auth/login")
                .successHandler((q,s,a) -> s.setStatus(204))
                .failureHandler((q,s,e) -> { s.setContentType("application/json"); s.setStatus(401);
                    s.getWriter().write("{\"code\":\"BAD_CREDENTIALS\"}"); }))
            .logout(l -> l.logoutUrl("/api/auth/logout").logoutSuccessHandler((q,s,a)->s.setStatus(204)))
            .exceptionHandling(e -> e.authenticationEntryPoint((q,s,x)->{
                    s.setStatus(401); s.setContentType("application/json"); s.getWriter().write("{\"code\":\"UNAUTHENTICATED\"}"); })
                .accessDeniedHandler((q,s,x)->{s.setStatus(403); s.setContentType("application/json");
                    s.getWriter().write("{\"code\":\"FORBIDDEN_OR_CSRF\"}");}));
        return http.build();
    }
    @Bean ApplicationRunner bootstrap(JdbcTemplate jdbc, PasswordEncoder encoder, TransactionTemplate tx,
            @Value("${signalforge.bootstrap-enabled}") boolean enabled,
            @Value("${signalforge.bootstrap-username}") String username,
            @Value("${signalforge.bootstrap-password}") String password) {
        return args -> {
            if (!enabled) return;
            if (password.length()<20 || username.isBlank()) throw new IllegalStateException("Bootstrap requires a strong development password");
            tx.executeWithoutResult(s -> {
                UUID user=UUID.nameUUIDFromBytes(("signalforge:dev:"+username).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                UUID project=UUID.fromString("00000000-0000-0000-0000-000000000001");
                jdbc.update("INSERT INTO sf_user(id,username,password_hash) VALUES(?,?,?) ON CONFLICT(username) DO NOTHING",user,username,encoder.encode(password));
                UUID actual=jdbc.queryForObject("SELECT id FROM sf_user WHERE username=?",UUID.class,username);
                jdbc.update("INSERT INTO sf_project(id,name) VALUES(?,?) ON CONFLICT DO NOTHING",project,"SignalForge 本地研究项目");
                jdbc.update("INSERT INTO sf_project_member(project_id,user_id,role) VALUES(?,?,'OWNER') ON CONFLICT DO NOTHING",project,actual);
            });
        };
    }
}
