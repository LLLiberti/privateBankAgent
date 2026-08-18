package com.privatebank.business.mapper.product;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ProductDocumentMapper {

    @Select("""
            <script>
            SELECT document_id
              FROM product_document
             WHERE product_id IN
             <foreach collection="productIds" item="productId" open="(" separator="," close=")">
                 #{productId}
             </foreach>
            </script>
            """)
    List<String> findDocumentIdsByProductIds(@Param("productIds") List<String> productIds);

    @Select("""
            SELECT product_id
              FROM product_document
             WHERE document_id = #{documentId}
             LIMIT 1
            """)
    String findProductIdByDocumentId(@Param("documentId") String documentId);
}
