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
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
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

    @CacheEvict(value = PRODUCT_CACHE, key = "#id")
    public ProductDto.ProductResponse updateProduct(UUID id, ProductDto.UpdateRequest request, UUID sellerId) {
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
