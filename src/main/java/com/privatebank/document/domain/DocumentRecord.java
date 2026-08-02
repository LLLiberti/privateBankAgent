package com.privatebank.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "document")
public class DocumentRecord {

    @Id
    @Column(name = "document_id", length = 64, nullable = false)
    private String documentId;

    @Column(name = "person_id")
    private Long personId;

    @Column(name = "file_name", length = 255, nullable = false)
    private String fileName;

    @Column(name = "file_type", length = 32, nullable = false)
    private String fileType;

    @Column(name = "file_path", length = 1000, nullable = false)
    private String filePath;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "publish_time")
    private LocalDateTime publishTime;

    @Column(name = "upload_time", nullable = false)
    private LocalDateTime uploadTime;

    @Column(name = "parse_status", length = 24, nullable = false)
    private String parseStatus;

    @Column(name = "parse_error_message", length = 1000)
    private String parseErrorMessage;

    @Column(name = "fact_count", nullable = false)
    private Integer factCount;
}
