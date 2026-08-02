package com.privatebank.business.entity.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@TableName("sys_user")
public class SysUser {

    @TableId(value = "user_id", type = IdType.INPUT)
    private String userId;

    @TableField("userAccount")
    private String userAccount;

    @TableField("userName")
    private String userName;

    private String passwordHash;

    private String roleId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
