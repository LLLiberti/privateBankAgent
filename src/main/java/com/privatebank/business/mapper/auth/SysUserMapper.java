package com.privatebank.business.mapper.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.privatebank.business.entity.auth.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("""
            SELECT user_id AS userId,
                   userAccount AS userAccount,
                   userName AS userName,
                   password_hash AS passwordHash,
                   role_id AS roleId,
                   created_at AS createdAt,
                   updated_at AS updatedAt
              FROM sys_user
             WHERE user_id = #{userId}
             FOR UPDATE
            """)
    SysUser selectByIdForUpdate(@Param("userId") String userId);
}
