package com.travelscope.agent;

import com.travelscope.config.AgentConfig;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脱稿工具收编（AgentConfig.revokeOffScriptTools）：框架默认把 read_file/write_file/
 * edit_file/web_search/web_fetch 等挂在 delegate toolkit（白名单视图管不到，探针实证），
 * 后置剪枝必须收掉 edit_file/write_file/web_search/web_fetch 且保留 read_file（回退路径）。
 */
class FilesystemToolProbeTest {

    @TempDir
    Path workspace;

    private HarnessAgent newAgent() {
        Model model = new Model() {
            public String getModelName() {
                return "probe-model";
            }

            public Flux<ChatResponse> stream(List<Msg> messages,
                                             List<ToolSchema> schemas, GenerateOptions options) {
                return Flux.just(ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("ok").build())).build());
            }
        };
        return HarnessAgent.builder()
                .name("probe-agent")
                .model(model)
                .toolkit(new Toolkit())
                .workspace(workspace.toString())
                .permissionContext(PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
                .disableDefaultWorkspaceSkills().disableShellTool()
                .maxIters(1)
                .build();
    }

    @Test
    void revokeStripsOffScriptToolsButKeepsReadFile() {
        HarnessAgent agent = newAgent();
        try {
            // 前置：框架默认文件/网页工具确实附着（不在共享 toolkit 白名单视野内）
            var names = agent.getDelegate().getToolkit().getToolNames();
            assertTrue(names.contains("edit_file") && names.contains("write_file")
                            && names.contains("read_file"),
                    "探针前提：框架默认工具应附着于 delegate toolkit: " + names);

            AgentConfig.revokeOffScriptTools(agent);

            var after = agent.getDelegate().getToolkit().getToolNames();
            assertFalse(after.contains("edit_file"), "edit_file 应收编: " + after);
            assertFalse(after.contains("write_file"), "write_file 应收编: " + after);
            assertFalse(after.contains("web_search"), "web_search 应收编: " + after);
            assertFalse(after.contains("web_fetch"), "web_fetch 应收编: " + after);
            assertTrue(after.contains("read_file"), "read_file 必须保留（缺内联节回退路径）: " + after);
        } finally {
            agent.close();
        }
    }
}
