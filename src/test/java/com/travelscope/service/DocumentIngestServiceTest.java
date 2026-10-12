package com.travelscope.service;

import com.travelscope.entity.Document;
import com.travelscope.repository.DocumentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DocumentIngestService 单测（P1-L 真实摄入链路，无环境门控）
 * <p>
 * 管线依赖全部打桩：MinioService/RagServiceImpl/EsBm25Client 用 Mockito，
 * DocumentRepository 用动态代理内存存储（TokenUsageServiceTest 同款模式），
 * DataSource 深桩（仅验证清理 SQL 路径不炸）。
 * </p>
 */
class DocumentIngestServiceTest {

    // ==================== 切块纯逻辑 ====================

    @Test
    @DisplayName("切块：短文本单块 / 空与全空白返回空 / 定长+重叠边界")
    void testChunking() {
        assertEquals(1, DocumentIngestService.chunk("短文本").size());
        assertEquals(1, DocumentIngestService.chunk("x".repeat(800)).size(), "恰好 800 字应单块");
        assertEquals(0, DocumentIngestService.chunk("").size());
        assertEquals(0, DocumentIngestService.chunk("  \n  ").size(), "全空白应无有效块");

        // 2000 字：起点 0 / 700 / 1400（步长 700，块长 800，重叠 100）→ 3 块
        List<String> pieces = DocumentIngestService.chunk("a".repeat(2000));
        assertEquals(3, pieces.size());
        assertEquals(800, pieces.get(0).length());
        assertEquals(600, pieces.get(2).length(), "末块=2000-1400=600");
    }

    // ==================== 管线 ====================

    /** 内存 DocumentRepository（findById/save 两方法） */
    private static DocumentRepository inMemoryRepo(Map<Long, Document> store) {
        return (DocumentRepository) java.lang.reflect.Proxy.newProxyInstance(
                DocumentIngestServiceTest.class.getClassLoader(),
                new Class<?>[]{DocumentRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.ofNullable(store.get((Long) args[0]));
                    case "save" -> {
                        Document d = (Document) args[0];
                        if (d.getId() == null) {
                            d.setId(1L);
                        }
                        store.put(d.getId(), d);
                        yield d;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Document newDoc() {
        Document d = new Document();
        d.setId(1L);
        d.setTitle("杭州攻略.md");
        d.setFileName("杭州攻略.md");
        d.setFileType("md");
        d.setFilePath("docs/20261011/abc-杭州攻略.md");
        d.setStatus(Document.STATUS_UPLOADED);
        return d;
    }

    private static javax.sql.DataSource stubDataSource() throws Exception {
        javax.sql.DataSource ds = Mockito.mock(javax.sql.DataSource.class);
        Connection conn = Mockito.mock(Connection.class);
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        Mockito.when(ds.getConnection()).thenReturn(conn);
        Mockito.when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);
        Mockito.when(ps.executeUpdate()).thenReturn(0);
        return ds;
    }

    private static void awaitStatus(Map<Long, Document> store, long id, String... targets)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String s = store.get(id).getStatus();
            for (String t : targets) {
                if (t.equals(s)) {
                    return;
                }
            }
            Thread.sleep(50);
        }
    }

    @Test
    @DisplayName("成功管线：md 文档 → PARSED + chunkCount 正确 + 双写各一次")
    void testSuccessfulIngest() throws Exception {
        String md = "# 杭州攻略\n" + "西湖免费开放，建议游玩4-6小时。".repeat(120);   // >1600 字 → 3 块
        Map<Long, Document> store = new HashMap<>();
        store.put(1L, newDoc());

        MinioService minio = Mockito.mock(MinioService.class);
        InputStream stream = new ByteArrayInputStream(md.getBytes(StandardCharsets.UTF_8));
        Mockito.when(minio.download(Mockito.anyString())).thenReturn(stream);
        RagServiceImpl rag = Mockito.mock(RagServiceImpl.class);
        Mockito.when(rag.addKnowledgeChunks(Mockito.anyList())).thenAnswer(
                inv -> ((List<?>) inv.getArgument(0)).size());
        EsBm25Client es = Mockito.mock(EsBm25Client.class);
        Mockito.when(es.bulkIndex(Mockito.anyList())).thenReturn(true);

        DocumentIngestService service = new DocumentIngestService(
                minio, inMemoryRepo(store), rag, es, stubDataSource());
        service.parseAsync(1L);
        awaitStatus(store, 1L, Document.STATUS_PARSED, Document.STATUS_FAILED);
        service.shutdownIngestExecutor();

        Document doc = store.get(1L);
        assertEquals(Document.STATUS_PARSED, doc.getStatus(), "应摄入成功: " + doc.getErrorMessage());
        int expectedChunks = DocumentIngestService.chunk(md).size();
        assertEquals(expectedChunks, doc.getChunkCount(), "chunkCount 应等于切块数");
        assertNull(doc.getErrorMessage());
        Mockito.verify(es, Mockito.times(1)).bulkIndex(Mockito.argThat(
                (List<EsBm25Client.SeedChunk> l) -> l.size() == expectedChunks
                        && "doc-1".equals(l.get(0).docId()) && "upload".equals(l.get(0).source())
                        && "md".equals(l.get(0).fileType())));
        Mockito.verify(rag, Mockito.times(1)).addKnowledgeChunks(Mockito.anyList());
    }

    @Test
    @DisplayName("失败管线：下载异常 → FAILED + errorMessage 回填 + 不触碰双写")
    void testFailedIngest() throws Exception {
        Map<Long, Document> store = new HashMap<>();
        store.put(1L, newDoc());

        MinioService minio = Mockito.mock(MinioService.class);
        Mockito.when(minio.download(Mockito.anyString()))
                .thenThrow(new IllegalStateException("MinIO 下载失败: 对象不存在"));
        RagServiceImpl rag = Mockito.mock(RagServiceImpl.class);
        EsBm25Client es = Mockito.mock(EsBm25Client.class);

        DocumentIngestService service = new DocumentIngestService(
                minio, inMemoryRepo(store), rag, es, stubDataSource());
        service.parseAsync(1L);
        awaitStatus(store, 1L, Document.STATUS_FAILED);
        service.shutdownIngestExecutor();

        Document doc = store.get(1L);
        assertEquals(Document.STATUS_FAILED, doc.getStatus());
        assertNotNull(doc.getErrorMessage(), "FAILED 应回填错误信息");
        assertTrue(doc.getErrorMessage().contains("下载失败"));
        Mockito.verify(es, Mockito.never()).bulkIndex(Mockito.anyList());
        Mockito.verify(rag, Mockito.never()).addKnowledgeChunks(Mockito.anyList());
    }
}
