package com.amazon.product.service;

import com.amazon.product.dto.ProductDto;
import com.amazon.product.entity.Product;
import com.amazon.product.exception.InsufficientStockException;
import com.amazon.product.exception.ResourceNotFoundException;
import com.amazon.product.repository.ProductRepository;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.StaleObjectStateException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ProductService {

    private final ProductRepository productRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    private static final String PRODUCT_EVENTS_TOPIC = "product.events";
    private static final String PRODUCT_CACHE = "products";

    // ⭐ Self-injection (lazy, field-based — not via the Lombok
    // @RequiredArgsConstructor constructor, same reason as OrderService's
    // equivalent field: @Lazy isn't reliably carried onto Lombok-generated
    // constructor parameters). Required so updateProduct() below calls
    // updateProductInternal() THROUGH the Spring proxy, not via a plain
    // `this.` call — @Transactional has no effect on same-class
    // self-invocation, a classic Spring AOP proxy limitation. @Lazy breaks
    // the circular dependency this would otherwise cause at bean-creation
    // time (this bean depending on a proxy of itself).
    @Autowired
    @Lazy
    private ProductService self;

    public ProductDto.ProductResponse createProduct(ProductDto.CreateRequest request, UUID sellerId) {
        Product product = Product.builder()
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .stockQuantity(request.getStockQuantity())
                .categoryId(request.getCategoryId())
                .sellerId(sellerId)
                .imageUrl(request.getImageUrl())
                .status(Product.ProductStatus.ACTIVE)
                .build();

        product = productRepository.save(product);
        log.info("Product created: {} by seller: {}", product.getId(), sellerId);

        publishProductEvent("PRODUCT_CREATED", product);
        meterRegistry.counter("products.created").increment();

        return mapToResponse(product);
    }

    @Cacheable(value = PRODUCT_CACHE, key = "#id")
    @Timed(value = "product.get", description = "Time to get product")
    @Transactional(readOnly = true)
    public ProductDto.ProductResponse getProductById(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
        return mapToResponse(product);
    }

    @Transactional(readOnly = true)
    public ProductDto.PagedProductResponse getProducts(int page, int size, String sortBy) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).descending());
        Page<Product> products = productRepository.findByStatus(Product.ProductStatus.ACTIVE, pageable);
        return mapToPagedResponse(products);
    }

    @Transactional(readOnly = true)
    public ProductDto.PagedProductResponse searchProducts(String query, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Product> products = productRepository.searchProducts(query, pageable);
        return mapToPagedResponse(products);
    }

    @Transactional(readOnly = true)
    public ProductDto.PagedProductResponse getProductsByCategory(UUID categoryId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Product> products = productRepository.findByCategoryIdAndStatus(
                categoryId, Product.ProductStatus.ACTIVE, pageable);
        return mapToPagedResponse(products);
    }

    /**
     * Update a product — thin retry wrapper.
     *
     * ⭐ SPLIT FROM the actual update logic (now updateProductInternal()).
     * Same fix, same reasoning, as OrderService.cancelOrder(): @Retryable
     * and @Transactional on the same method is a fragile Spring
     * anti-pattern (proxy ordering isn't guaranteed), which can leave a
     * retry attempt reusing a rollback-marked transaction or a stale
     * persistence-context read. Confirmed via ProductUpdateConcurrencyTest
     * that, before this fix, Product had no @Version at all, so two
     * concurrent updates to different fields silently lost one of them —
     * every save "succeeded" (200) with no conflict ever detected.
     *
     * @CacheEvict stays on this outer method — it only fires after a
     * successful return, so a failed/retried attempt never evicts
     * prematurely, and it's naturally idempotent if it ever did fire more
     * than once, unlike the transaction/persistence-context state that
     * forced the cancelOrder() split in the first place.
     */
    @Retryable(
            retryFor = {ObjectOptimisticLockingFailureException.class, StaleObjectStateException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @CacheEvict(value = PRODUCT_CACHE, key = "#id")
    public ProductDto.ProductResponse updateProduct(UUID id, ProductDto.UpdateRequest request, UUID sellerId) {
        return self.updateProductInternal(id, request, sellerId);
    }

    /**
     * Actual update logic — runs in its own fresh transaction on every
     * call, including every retry attempt from updateProduct() above.
     * Kept public (not private/protected) so Spring's CGLIB proxy can
     * genuinely override it to apply @Transactional advice — should still
     * only ever be called via updateProduct(), not invoked directly from
     * outside this class.
     */
    @Transactional
    public ProductDto.ProductResponse updateProductInternal(UUID id, ProductDto.UpdateRequest request, UUID sellerId) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));

        if (!product.getSellerId().equals(sellerId)) {
            throw new SecurityException("Not authorized to update this product");
        }

        if (request.getName() != null) product.setName(request.getName());
        if (request.getDescription() != null) product.setDescription(request.getDescription());
        if (request.getPrice() != null) product.setPrice(request.getPrice());
        if (request.getStockQuantity() != null) product.setStockQuantity(request.getStockQuantity());
        if (request.getCategoryId() != null) product.setCategoryId(request.getCategoryId());
        if (request.getImageUrl() != null) product.setImageUrl(request.getImageUrl());
        if (request.getStatus() != null) product.setStatus(request.getStatus());

        product = productRepository.save(product);
        publishProductEvent("PRODUCT_UPDATED", product);
        return mapToResponse(product);
    }

    public void updateStock(UUID productId, int quantity) {
        int updated = productRepository.updateStock(productId, quantity);
        if (updated == 0) {
            throw new InsufficientStockException("Insufficient stock for product: " + productId);
        }
        log.info("Stock updated for product {} by {}", productId, quantity);
    }

    @CacheEvict(value = PRODUCT_CACHE, key = "#id")
    public void deleteProduct(UUID id, UUID sellerId) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));

        if (!product.getSellerId().equals(sellerId)) {
            throw new SecurityException("Not authorized to delete this product");
        }

        product.setStatus(Product.ProductStatus.DISCONTINUED);
        productRepository.save(product);
        publishProductEvent("PRODUCT_DELETED", product);
    }

    private void publishProductEvent(String eventType, Product product) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", eventType);
        event.put("productId", product.getId());
        event.put("sellerId", product.getSellerId());
        event.put("price", product.getPrice());
        event.put("stockQuantity", product.getStockQuantity());
        kafkaTemplate.send(PRODUCT_EVENTS_TOPIC, product.getId().toString(), event);
    }

    private ProductDto.ProductResponse mapToResponse(Product product) {
        return ProductDto.ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .stockQuantity(product.getStockQuantity())
                .categoryId(product.getCategoryId())
                .sellerId(product.getSellerId())
                .imageUrl(product.getImageUrl())
                .rating(product.getRating())
                .reviewCount(product.getReviewCount())
                .status(product.getStatus())
                .createdAt(product.getCreatedAt())
                .build();
    }

    private ProductDto.PagedProductResponse mapToPagedResponse(Page<Product> page) {
        return ProductDto.PagedProductResponse.builder()
                .products(page.getContent().stream().map(this::mapToResponse).toList())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .build();
    }
}