package com.privatebank.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "sys_role")
public class SysRole {

    @Id
    @Column(name = "role_id", length = 64, nullable = false)
    private String roleId;

    @Column(name = "role_name", length = 32, nullable = false, unique = true)
    private String roleName;
}
