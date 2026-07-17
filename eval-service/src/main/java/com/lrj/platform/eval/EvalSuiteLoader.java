package com.lrj.platform.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.eval.EvalSuiteDefinition;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 评测用例集（baseline suite）加载器。按名字先从外部基线目录
 * （{@link EvalProperties#getBaselineDirectory()}）读取 {@code <name>.json}，找不到再回退到 classpath
 * 下的 {@code eval/baselines/}，解析为 {@link com.lrj.platform.protocol.eval.EvalSuiteDefinition}。
 * suite 名做白名单校验并对目录做路径穿越防护；缺失时抛 {@link SuiteNotFoundException}。
 */
@Service
public class EvalSuiteLoader {

    private static final Pattern SAFE_SUITE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private final ObjectMapper objectMapper;
    private final EvalProperties properties;

    public EvalSuiteLoader(ObjectMapper objectMapper, EvalProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public EvalSuiteDefinition load(String suiteName) {
        if (suiteName == null || !SAFE_SUITE_NAME.matcher(suiteName).matches()) {
            throw new IllegalArgumentException("invalid suite name");
        }
        try {
            return loadFromExternalDirectory(suiteName);
        } catch (SuiteNotFoundException ignored) {
            return loadFromClasspath(suiteName);
        }
    }

    private EvalSuiteDefinition loadFromExternalDirectory(String suiteName) {
        String directory = properties.getBaselineDirectory();
        if (directory == null || directory.isBlank()) {
            throw new SuiteNotFoundException();
        }
        Path base = Path.of(directory).toAbsolutePath().normalize();
        Path file = base.resolve(suiteName + ".json").normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            throw new SuiteNotFoundException();
        }
        try (InputStream input = Files.newInputStream(file)) {
            return objectMapper.readValue(input, EvalSuiteDefinition.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to load eval suite: " + suiteName, ex);
        }
    }

    private EvalSuiteDefinition loadFromClasspath(String suiteName) {
        ClassPathResource resource = new ClassPathResource("eval/baselines/" + suiteName + ".json");
        if (!resource.exists()) {
            throw new SuiteNotFoundException();
        }
        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readValue(input, EvalSuiteDefinition.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to load eval suite: " + suiteName, ex);
        }
    }

    public static class SuiteNotFoundException extends RuntimeException {
    }
}
