package com.amazon.product.controller;

import com.amazon.product.dto.ProductDto;
import com.amazon.product.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping
    public ResponseEntity<ProductDto.ProductResponse> createProduct(
            @Valid @RequestBody ProductDto.CreateRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String sellerId) {
        UUID sellerUUID = sellerId != null ? UUID.fromString(sellerId) : UUID.randomUUID();
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
        UUID sellerUUID = sellerId != null ? UUID.fromString(sellerId) : UUID.randomUUID();
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
        UUID sellerUUID = sellerId != null ? UUID.fromString(sellerId) : UUID.randomUUID();
        productService.deleteProduct(id, sellerUUID);
        return ResponseEntity.noContent().build();
    }
}
