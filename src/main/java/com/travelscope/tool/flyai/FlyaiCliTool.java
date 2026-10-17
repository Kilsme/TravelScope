package com.travelscope.tool.flyai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 飞猪 flyai CLI 调用底座（F1：ProcessBuilder 子进程调 @fly-ai/flyai-cli）。
 * <p>
 * 关键处理：
 * <ul>
 *   <li><b>UTF-8 读取 stdout</b>——Windows 默认 GBK 会把 CLI 的 UTF-8 输出读成乱码
 *       （实测踩坑），必须显式 {@link StandardCharsets#UTF_8}</li>
 *   <li><b>env 透传</b>：FLYAI_API_KEY 从配置注入子进程环境（为空时 CLI 走免 key 体验模式）</li>
 *   <li><b>超时</b>：waitFor 超时 destroyForcibly，防 Node 子进程悬挂</li>
 *   <li><b>错误归一化</b>：进程失败/超时/非 JSON 输出统一转 {@code {"error": "..."}}，
 *       与 TransportTool 的错误风格一致</li>
 *   <li>stderr 的 Node UV_HANDLE_CLOSING 警告属已知无害噪音，仅 debug 记录</li>
 * </ul>
 * Windows 下经 {@code cmd /c npx} 拉起（npx 是批处理脚本，与项目 12306 MCP 同款模式）。
 * </p>
 */
@Component
public class FlyaiCliTool {

    private static final Logger log = LoggerFactory.getLogger(FlyaiCliTool.class);

    private final AppProperties appProperties;

    public FlyaiCliTool(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    // ==================== 类型化命令（供各业务 Tool 调用） ====================

    /** 酒店搜索：destName 必填；keyWords（酒店名/品牌）可空 */
    public JSONObject searchHotel(String destName, String keyWords, Integer maxPrice) {
        List<String> args = new ArrayList<>(List.of("search-hotel", "--dest-name", destName));
        if (keyWords != null && !keyWords.isBlank()) {
            args.addAll(List.of("--key-words", keyWords));
        }
        if (maxPrice != null) {
            args.addAll(List.of("--max-price", String.valueOf(maxPrice)));
        }
        return call(args);
    }

    /** 景点搜索：cityName 必填；keyword 可空 */
    public JSONObject searchPoi(String cityName, String keyword) {
        List<String> args = new ArrayList<>(List.of("search-poi", "--city-name", cityName));
        if (keyword != null && !keyword.isBlank()) {
            args.addAll(List.of("--keyword", keyword));
        }
        return call(args);
    }

    /** 火车票搜索：origin 必填；destination/depDate 可空 */
    public JSONObject searchTrain(String origin, String destination, String depDate) {
        List<String> args = new ArrayList<>(List.of("search-train", "--origin", origin));
        if (destination != null && !destination.isBlank()) {
            args.addAll(List.of("--destination", destination));
        }
        if (depDate != null && !depDate.isBlank()) {
            args.addAll(List.of("--dep-date", depDate));
        }
        return call(args);
    }

    /** 机票搜索：origin 必填；destination/depDate 可空 */
    public JSONObject searchFlight(String origin, String destination, String depDate) {
        List<String> args = new ArrayList<>(List.of("search-flight", "--origin", origin));
        if (destination != null && !destination.isBlank()) {
            args.addAll(List.of("--destination", destination));
        }
        if (depDate != null && !depDate.isBlank()) {
            args.addAll(List.of("--dep-date", depDate));
        }
        return call(args);
    }

    // ==================== 进程调用核心 ====================

    /**
     * 调用 flyai CLI 并解析 stdout JSON。
     *
     * @return 响应 JSON（含 data/message/status/systemMessage）；失败返回 {@code {"error": ...}}
     */
    public JSONObject call(List<String> args) {
        var config = appProperties.getFlyai();
        String cli = config.getCliPackage();
        List<String> command = new ArrayList<>();
        // 「@」开头 → npx 包模式（首次冷启动慢）；否则视为全局已安装命令直接调（快）
        if (cli.startsWith("@")) {
            if (isWindows()) {
                command.addAll(List.of("cmd", "/c", "npx", "-y", cli));
            } else {
                command.addAll(List.of("npx", "-y", cli));
            }
        } else {
            if (isWindows()) {
                command.addAll(List.of("cmd", "/c", cli));
            } else {
                command.add(cli);
            }
        }
        command.addAll(args);

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            String apiKey = config.getApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                pb.environment().put("FLYAI_API_KEY", apiKey);
            }
            pb.redirectErrorStream(false);
            long start = System.nanoTime();
            process = pb.start();
            // 立即关闭子进程 stdin 写端（给 EOF）
            process.getOutputStream().close();

            // stderr 异步 drain（防缓冲满阻塞；UV_HANDLE 等噪音仅 debug）
            StringBuilder stderr = new StringBuilder();
            final Process procForDrain = process;
            Thread stderrDrainer = new Thread(() -> drain(procForDrain, stderr), "flyai-stderr");
            stderrDrainer.setDaemon(true);
            stderrDrainer.start();

            // 读 stdout 首个非空行（flyai 协议为单行 JSON，SKILL.md 明确）；
            // flyai 输出完 JSON 后进程不退出（实测行为）——数据到手即销毁，不等自然退出
            String stdout;
            try (java.util.concurrent.ExecutorService reader =
                         java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                final Process proc = process;
                java.util.concurrent.Future<String> future = reader.submit(() -> readFirstLine(proc));
                stdout = future.get(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                process.destroyForcibly();
                log.warn("flyai CLI 超时（{}s）: {} stderr={}", config.getTimeoutSeconds(),
                        args.get(0), abbrev(stderr));
                return errorResult("飞猪查询超时（" + config.getTimeoutSeconds() + "s）");
            } finally {
                process.destroyForcibly();
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            JSONObject parsed = parseResponse(stdout);
            log.info("flyai CLI 调用完成: cmd={}, duration={}ms", args.get(0), ms);
            return parsed;
        } catch (Exception e) {
            log.warn("flyai CLI 调用异常: {}: {}", args.get(0), e.getMessage());
            return errorResult("飞猪查询异常: " + e.getMessage());
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    /** 读 stdout 首个非空行（flyai 协议为单行 JSON，SKILL.md 明确），UTF-8 显式读取 */
    private static String readFirstLine(Process process) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    return line;
                }
            }
            return "";
        }
    }

    /** stdout 全量读取（显式 UTF-8——Windows GBK 默认会把 CLI 输出读成乱码） */
    static String readStdout(Process process) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static void drain(Process process, StringBuilder sink) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sink.append(line).append('\n');
            }
        } catch (Exception ignored) {
            // 进程销毁时流关闭，正常
        }
    }

    /** 响应解析：单层 JSON 文本 → JSONObject；非 JSON（如 ai-search 的文本）或解析失败归一化 */
    static JSONObject parseResponse(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return errorResult("飞猪查询无输出");
        }
        try {
            JSONObject object = JSON.parseObject(stdout.trim());
            if (object == null) {
                return errorResult("飞猪查询返回为空");
            }
            return object;
        } catch (Exception e) {
            return errorResult("飞猪查询返回解析失败: " + abbrev(stdout, 100));
        }
    }

    /** 提取 data.itemList（无则空数组） */
    public static com.alibaba.fastjson2.JSONArray itemListOf(JSONObject response) {
        if (response == null) {
            return new com.alibaba.fastjson2.JSONArray();
        }
        JSONObject data = response.getJSONObject("data");
        if (data == null) {
            return new com.alibaba.fastjson2.JSONArray();
        }
        com.alibaba.fastjson2.JSONArray list = data.getJSONArray("itemList");
        return list == null ? new com.alibaba.fastjson2.JSONArray() : list;
    }

    /** 统一错误结构（包级可见，供测试与调用方构造降级响应） */
    public static JSONObject errorResult(String message) {
        JSONObject error = new JSONObject();
        error.put("error", message);
        return error;
    }

    /** 是否有可用数据（非 error 且 itemList 非空） */
    public static boolean hasItems(JSONObject response) {
        return response != null && !response.containsKey("error") && !itemListOf(response).isEmpty();
    }

    private static String abbrev(StringBuilder sb) {
        return abbrev(sb.toString(), 120);
    }

    private static String abbrev(String s, int limit) {
        if (s == null) {
            return "";
        }
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
