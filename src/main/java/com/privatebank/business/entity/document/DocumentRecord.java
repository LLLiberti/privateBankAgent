package com.privatebank.business.entity.document;

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
@TableName("document")
public class DocumentRecord {

    @TableId(value = "document_id", type = IdType.INPUT)
    private String documentId;

    private Long personId;

    private String fileName;

    private String fileType;

    private String filePath;

    private Long sourceId;

    private LocalDateTime publishTime;

    private LocalDateTime uploadTime;

    private String parseStatus;

    private String parseErrorMessage;

    private Integer factCount;
}
