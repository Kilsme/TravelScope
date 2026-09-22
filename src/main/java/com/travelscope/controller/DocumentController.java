package com.travelscope.controller;

import com.travelscope.entity.Document;
import com.travelscope.security.AuthInterceptor;
import com.travelscope.service.DocumentService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档管理接口（FR-A03：上传 + 列表，管理端）
 * <p>
 * multipart 上传 → MinIO 存储 + documents 状态机（UPLOADED→PARSING 占位）。
 * 全部要求 role=admin（AdminController 同款 requireAdmin 校验）。
 * </p>
 */
@RestController
@RequestMapping("/admin/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * 上传文档（multipart/form-data，file 字段）
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    HttpServletRequest request) {
        ResponseEntity<?> forbidden = requireAdmin(request);
        if (forbidden != null) {
            return forbidden;
        }
        Document doc = documentService.upload(file);
        Map<String, Object> body = new HashMap<>();
        body.put("id", doc.getId());
        body.put("fileName", doc.getFileName());
        body.put("fileSize", doc.getFileSize());
        body.put("status", doc.getStatus());
        body.put("filePath", doc.getFilePath());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * 文档列表（含状态徽章展示数据）
     */
    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        ResponseEntity<?> forbidden = requireAdmin(request);
        if (forbidden != null) {
            return forbidden;
        }
        List<Document> docs = documentService.listAll();
        return ResponseEntity.ok(docs.stream().map(this::toVO).toList());
    }

    private ResponseEntity<?> requireAdmin(HttpServletRequest request) {
        String role = (String) request.getAttribute(AuthInterceptor.ATTR_USER_ROLE);
        if (!"admin".equals(role)) {
            Map<String, Object> body = new HashMap<>();
            body.put("code", HttpStatus.FORBIDDEN.value());
            body.put("message", "需要管理员权限");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }
        return null;
    }

    private Map<String, Object> toVO(Document d) {
        Map<String, Object> vo = new HashMap<>();
        vo.put("id", d.getId());
        vo.put("title", d.getTitle());
        vo.put("fileName", d.getFileName());
        vo.put("fileType", d.getFileType());
        vo.put("fileSize", d.getFileSize());
        vo.put("status", d.getStatus());
        vo.put("filePath", d.getFilePath());
        vo.put("createdAt", d.getCreatedAt());
        vo.put("updatedAt", d.getUpdatedAt());
        return vo;
    }
}
