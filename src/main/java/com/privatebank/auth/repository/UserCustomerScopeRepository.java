package com.privatebank.auth.repository;

import com.privatebank.auth.domain.UserCustomerScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserCustomerScopeRepository extends JpaRepository<UserCustomerScope, Long> {

    boolean existsByUserIdAndPersonIdAndScopeStatus(String userId, Long personId, Integer scopeStatus);

    long countByUserIdAndScopeStatus(String userId, Integer scopeStatus);

    @Query("select s.personId from UserCustomerScope s where s.userId = :userId and s.scopeStatus = 1")
    List<Long> findActivePersonIds(@Param("userId") String userId);

    Optional<UserCustomerScope> findByUserIdAndPersonId(String userId, Long personId);
}
