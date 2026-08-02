package com.privatebank.auth.repository;

import com.privatebank.auth.domain.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUser, String> {

    Optional<SysUser> findByUserAccountIgnoreCase(String userAccount);

    boolean existsByUserAccountIgnoreCase(String userAccount);
}
