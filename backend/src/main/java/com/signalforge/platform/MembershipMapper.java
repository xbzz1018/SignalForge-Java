package com.signalforge.platform;

import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MembershipMapper {
    @Select("SELECT m.role FROM sf_project_member m JOIN sf_user u ON u.id=m.user_id WHERE m.project_id=#{project} AND u.username=#{username} AND u.enabled=true")
    String role(@Param("project") UUID project, @Param("username") String username);
}
