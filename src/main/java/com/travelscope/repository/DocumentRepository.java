package com.travelscope.repository;

import com.travelscope.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Document 仓库（FR-A03 文档管理）
 */
public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByStatusOrderByCreatedAtDesc(String status);

    List<Document> findAllByOrderByCreatedAtDesc();
}
