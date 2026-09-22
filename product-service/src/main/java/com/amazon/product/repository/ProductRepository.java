package com.amazon.product.repository;

import com.amazon.product.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    Page<Product> findByStatus(Product.ProductStatus status, Pageable pageable);

    Page<Product> findByCategoryIdAndStatus(UUID categoryId, Product.ProductStatus status, Pageable pageable);

    Page<Product> findBySellerIdAndStatus(UUID sellerId, Product.ProductStatus status, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.status = 'ACTIVE' AND " +
            "(LOWER(p.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<Product> searchProducts(@Param("query") String query, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.status = 'ACTIVE' AND " +
            "p.price BETWEEN :minPrice AND :maxPrice")
    Page<Product> findByPriceRange(@Param("minPrice") BigDecimal minPrice,
                                   @Param("maxPrice") BigDecimal maxPrice,
                                   Pageable pageable);

    /**
     * ⭐ FIXED — added `p.version = p.version + 1` to the SET clause.
     *
     * @Modifying queries execute raw SQL directly against the table —
     * they bypass Hibernate's entity lifecycle entirely, which means they
     * do NOT automatically increment @Version the way a normal save() does.
     * Without this explicit bump, adding @Version to Product would NOT
     * actually close the lost-update gap for stock changes specifically:
     * a concurrent updateProduct() call that read the product before this
     * ran would still see a matching (stale) version number and overwrite
     * the stock change on its own save(), completely undetected — same bug,
     * different door.
     */
    @Modifying
    @Query("UPDATE Product p SET p.stockQuantity = p.stockQuantity + :quantity, " +
            "p.version = p.version + 1 " +
            "WHERE p.id = :id AND p.stockQuantity + :quantity >= 0")
    int updateStock(@Param("id") UUID id, @Param("quantity") int quantity);

    List<Product> findByIdIn(List<UUID> ids);

    @Query("SELECT p FROM Product p WHERE p.stockQuantity <= :threshold AND p.status = 'ACTIVE'")
    List<Product> findLowStockProducts(@Param("threshold") int threshold);
}