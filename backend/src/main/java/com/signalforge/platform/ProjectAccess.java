package com.signalforge.platform;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class ProjectAccess {
    private final MembershipMapper members;
    private final JdbcTemplate jdbc;
    public ProjectAccess(MembershipMapper members, JdbcTemplate jdbc) { this.members=members; this.jdbc=jdbc; }
    public UUID require(UUID project, Authentication auth, boolean write) {
        String role=members.role(project, auth.getName());
        if (role==null || (write && !role.equals("OWNER")))
            throw new ApiException(403,"PROJECT_FORBIDDEN","无此项目的访问权限");
        return jdbc.queryForObject("SELECT id FROM sf_user WHERE username=? AND enabled=true",UUID.class,auth.getName());
    }
}
