package com.privatebank.customer.repository;

import com.privatebank.customer.domain.CustomerPersonalizedFact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerPersonalizedFactRepository extends JpaRepository<CustomerPersonalizedFact, String> {

    List<CustomerPersonalizedFact> findByPersonIdOrderByCreatedAtDesc(Long personId);
}
