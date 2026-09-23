package com.travelscope.controller;

import com.travelscope.entity.User;
import com.travelscope.service.AuthService;
import com.travelscope.service.ChatService;
import com.travelscope.service.ConversationService;
import com.travelscope.service.DocumentService;
import com.travelscope.service.TokenUsageService;
import com.travelscope.service.UserAdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端 API WebMvc 测试（FR-A01~A03 检测标准 1/4 的接口层验证）
 * <p>
 * 首个 @WebMvcTest（项目引入）：加载 Controller 层 + AuthInterceptor，
 * MockBean 业务服务；MockMvc 验证 401/200/403 语义与文档上传。
 * properties 禁用 JPA auditing（WebMvcTest 只加载 Web 层，不含 JPA MappingContext，
 * @EnableJpaAuditing 的 handler 会因缺上下文失败——标准排除法）。
 * </p>
 */
@WebMvcTest(properties = {
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration," +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "spring.data.jpa.repositories.bootstrap-mode=lazy"
})
class AdminApiWebMvcTest {

    /**
     * 内部配置：替换默认主类加载（主类 @EnableJpaAuditing 在无 JPA 上下文会初始化失败），
     * 用 @ComponentScan 限定只扫 controller/security/config 包（避开 JPA/Agent 全量初始化）。
     */
    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.context.annotation.ComponentScan(basePackages = {
            "com.travelscope.controller",
            "com.travelscope.security"
    })
    static class TestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserAdminService userAdminService;

    @MockBean
    private TokenUsageService tokenUsageService;

    @MockBean
    private DocumentService documentService;

    @MockBean
    private AuthService authService;

    // ChatController 的依赖（AdminController 不含，但 WebMvcTest 扫包可能加载——防御性 mock）
    @MockBean
    private ChatService chatService;

    @MockBean
    private ConversationService conversationService;

    // AdminController 的过载降级状态查询依赖（2026-09-23 并发改造新增）
    @MockBean
    private com.travelscope.service.LoadShedService loadShedService;

    private static User adminUser() {
        User u = new User();
        u.setId(1L);
        u.setUsername("admin");
        u.setRole("admin");
        u.setStatus((short) 1);
        return u;
    }

    private static User normalUser() {
        User u = new User();
        u.setId(2L);
        u.setUsername("user1");
        u.setRole("user");
        u.setStatus((short) 1);
        return u;
    }

    @Test
    @DisplayName("检测标准1：无 token 访问 /admin/users → 401 {code:401}")
    void testNoToken401() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("admin token 访问 /admin/users → 200 用户列表")
    void testAdminToken200() throws Exception {
        when(authService.resolveUser("admin-token")).thenReturn(Optional.of(adminUser()));
        when(userAdminService.listAll()).thenReturn(List.of(adminUser(), normalUser()));

        mockMvc.perform(get("/admin/users")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("admin"));
    }

    @Test
    @DisplayName("非 admin role 访问 /admin/users → 403 需要管理员权限")
    void testNonAdmin403() throws Exception {
        when(authService.resolveUser("user-token")).thenReturn(Optional.of(normalUser()));

        mockMvc.perform(get("/admin/users")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("需要管理员权限"));
    }

    @Test
    @DisplayName("无效 token → 401")
    void testInvalidToken401() throws Exception {
        when(authService.resolveUser("bad-token")).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/users")
                        .header("Authorization", "Bearer bad-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("禁用用户操作 → 200 返回确认（admin）")
    void testDisableUser200() throws Exception {
        when(authService.resolveUser("admin-token")).thenReturn(Optional.of(adminUser()));

        mockMvc.perform(put("/admin/users/2/disable")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("用户已禁用"));
    }

    @Test
    @DisplayName("检测标准4 接口层：multipart 上传 → 201 + status=UPLOADED")
    void testDocumentUpload201() throws Exception {
        when(authService.resolveUser("admin-token")).thenReturn(Optional.of(adminUser()));
        com.travelscope.entity.Document doc = new com.travelscope.entity.Document();
        doc.setId(1L);
        doc.setFileName("test.pdf");
        doc.setFileSize(1234L);
        doc.setStatus(com.travelscope.entity.Document.STATUS_UPLOADED);
        when(documentService.upload(any())).thenReturn(doc);

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "fake-pdf-content".getBytes());

        mockMvc.perform(multipart("/admin/documents")
                        .file(file)
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("test.pdf"))
                .andExpect(jsonPath("$.status").value("UPLOADED"));
    }
}
