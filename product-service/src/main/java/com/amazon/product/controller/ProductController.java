package com.amazon.product.controller;

import com.amazon.product.dto.ProductDto;
import com.amazon.product.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Slf4j
public class ProductController {

    private final ProductService productService;

    @PostMapping
    public ResponseEntity<ProductDto.ProductResponse> createProduct(
            @Valid @RequestBody ProductDto.CreateRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String sellerId) {

        // ⭐ FIXED — was `sellerId != null ? UUID.fromString(sellerId) :
        // UUID.randomUUID()`. A missing header silently created a product
        // owned by a random, real-user UUID — an orphaned, unmanageable
        // product, with no error at all. Now matches OrderController's
        // correct handling of the same situation: reject with 400.
        if (sellerId == null || sellerId.isBlank()) {
            log.error("Missing X-User-Id header");
            return ResponseEntity.badRequest().build();
        }

        UUID sellerUUID = UUID.fromString(sellerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productService.createProduct(request, sellerUUID));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductDto.ProductResponse> getProduct(@PathVariable UUID id) {
        return ResponseEntity.ok(productService.getProductById(id));
    }

    @GetMapping
    public ResponseEntity<ProductDto.PagedProductResponse> getProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy) {
        return ResponseEntity.ok(productService.getProducts(page, size, sortBy));
    }

    @GetMapping("/search")
    public ResponseEntity<ProductDto.PagedProductResponse> searchProducts(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(productService.searchProducts(q, page, size));
    }

    @GetMapping("/category/{categoryId}")
    public ResponseEntity<ProductDto.PagedProductResponse> getByCategory(
            @PathVariable UUID categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(productService.getProductsByCategory(categoryId, page, size));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductDto.ProductResponse> updateProduct(
            @PathVariable UUID id,
            @Valid @RequestBody ProductDto.UpdateRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String sellerId) {

        // ⭐ FIXED — same issue as createProduct(): a missing header
        // previously got a random UUID substituted in, which would almost
        // certainly fail ProductService's ownership check anyway — but as a
        // confusing 403 ("not authorized"), not a clear 400 ("you forgot
        // the header"). Now rejected explicitly, matching OrderController.
        if (sellerId == null || sellerId.isBlank()) {
            log.error("Missing X-User-Id header");
            return ResponseEntity.badRequest().build();
        }

        UUID sellerUUID = UUID.fromString(sellerId);
        return ResponseEntity.ok(productService.updateProduct(id, request, sellerUUID));
    }

    @PatchMapping("/{id}/stock")
    public ResponseEntity<Void> updateStock(
            @PathVariable UUID id,
            @RequestParam int quantity) {
        productService.updateStock(id, quantity);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", required = false) String sellerId) {

        // ⭐ FIXED — same issue as createProduct()/updateProduct().
        if (sellerId == null || sellerId.isBlank()) {
            log.error("Missing X-User-Id header");
            return ResponseEntity.badRequest().build();
        }

        UUID sellerUUID = UUID.fromString(sellerId);
        productService.deleteProduct(id, sellerUUID);
        return ResponseEntity.noContent().build();
    }
}