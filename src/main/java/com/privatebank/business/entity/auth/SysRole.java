package com.privatebank.business.entity.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("sys_role")
public class SysRole {

    @TableId(value = "role_id", type = IdType.INPUT)
    private String roleId;

    private String roleName;
}
