package com.privatebank.business.mapper.product;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ProductDocumentMapper {

    @ConstructorArgs({
            @Arg(column = "product_id", javaType = String.class),
            @Arg(column = "document_id", javaType = String.class)
    })
    @Select("""
            <script>
            SELECT product_id, document_id
              FROM product_document
             WHERE product_id IN
             <foreach collection="productIds" item="productId" open="(" separator="," close=")">
                 #{productId}
             </foreach>
            </script>
            """)
    List<ProductDocumentLink> findLinksByProductIds(@Param("productIds") List<String> productIds);
}
