package com.privatebank.auth.repository;

import com.privatebank.auth.domain.SysRole;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SysRoleRepository extends JpaRepository<SysRole, String> {
}
