package com.lrj.platform.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.eval.EvalRunReply;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 评测报告落盘器。当 {@link EvalProperties#getReportDirectory()} 配置了目录时，把一次运行的
 * {@link com.lrj.platform.protocol.eval.EvalRunReply} 以 runId 命名写成 JSON 文件（含路径穿越校验）；
 * 未配置目录则跳过并返回空。
 */
@Service
public class EvalReportWriter {

    private final ObjectMapper objectMapper;
    private final EvalProperties properties;

    public EvalReportWriter(ObjectMapper objectMapper, EvalProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<String> write(EvalRunReply report) {
        String directory = properties.getReportDirectory();
        if (directory == null || directory.isBlank()) {
            return Optional.empty();
        }
        try {
            Path dir = Path.of(directory).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path file = dir.resolve(safeFileName(report.runId()) + ".json").normalize();
            if (!file.startsWith(dir)) {
                throw new IllegalArgumentException("invalid report path");
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
            return Optional.of(file.toString());
        } catch (IOException ex) {
            throw new IllegalStateException("failed to write eval report", ex);
        }
    }

    private String safeFileName(String runId) {
        String value = runId == null || runId.isBlank() ? "eval-report" : runId;
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
