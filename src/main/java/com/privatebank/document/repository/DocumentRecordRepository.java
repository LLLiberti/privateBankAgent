package com.privatebank.document.repository;

import com.privatebank.document.domain.DocumentRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRecordRepository extends JpaRepository<DocumentRecord, String> {

    Page<DocumentRecord> findByPersonId(Long personId, Pageable pageable);
}
