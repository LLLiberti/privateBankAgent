package com.privatebank.business.entity.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@TableName("user_customer_scope")
public class UserCustomerScope {

    @TableId(value = "scope_id", type = IdType.AUTO)
    private Long scopeId;

    private String userId;

    private Long personId;

    private Integer scopeStatus;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
