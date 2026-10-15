package com.travelscope.collab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 工作区契约文件统一读取入口（S0.4）。
 * <p>
 * 读到的内容必须过 {@link CollabSchemaValidator} 全量校验：坏文件抛
 * {@link CollabValidationException}（消息含绝对路径，供快速定位），
 * 绝不静默降级返回半个坏对象（conv-97 教训：坏内容被静默消费比报错更难查）。
 * </p>
 */
@Service
public class CollabFileReader {

    private static final Logger log = LoggerFactory.getLogger(CollabFileReader.class);

    /**
     * 读取契约文件：不存在返回 {@code Optional.empty()}；存在则校验后返回 DTO。
     *
     * @throws CollabValidationException 文件存在但内容非法（消息含绝对路径）
     * @throws UncheckedIOException      磁盘读取失败
     */
    public <T> Optional<T> read(Path dir, String fileName, Class<T> type) {
        Path file = dir.resolve(fileName);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取契约文件失败: " + file, e);
        }
        try {
            return Optional.of(CollabSchemaValidator.parse(json, type));
        } catch (CollabValidationException e) {
            // 包装一层补绝对路径（坏文件定位到具体会话目录）
            throw new CollabValidationException(e.getMessage() + "；文件: " + file, e);
        }
    }

    /**
     * 业务必选读取：缺文件直接抛 {@link CollabValidationException}。
     */
    public <T> T readOrThrow(Path dir, String fileName, Class<T> type) {
        return read(dir, fileName, type).orElseThrow(() ->
                new CollabValidationException("[" + type.getSimpleName() + "] 契约文件缺失: "
                        + dir.resolve(fileName)));
    }
}
