package com.lrj.platform.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.eval.EvalOracleSnapshot;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 加载冻结 oracle 快照（PR 模式的轻量基准）。
 *
 * <p>与 {@link EvalSuiteLoader} 同构：优先从外部目录 {@code app.eval.snapshot-directory} 找
 * {@code <name>.json}，找不到再回退 classpath {@code eval/snapshots/<name>.json}。名字做白名单校验 +
 * 路径逃逸防护。
 */
@Service
public class EvalSnapshotLoader {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private final ObjectMapper objectMapper;
    private final EvalProperties properties;

    public EvalSnapshotLoader(ObjectMapper objectMapper, EvalProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public EvalOracleSnapshot load(String snapshotName) {
        if (snapshotName == null || !SAFE_NAME.matcher(snapshotName).matches()) {
            throw new IllegalArgumentException("invalid snapshot name");
        }
        try {
            return loadFromExternalDirectory(snapshotName);
        } catch (SnapshotNotFoundException ignored) {
            return loadFromClasspath(snapshotName);
        }
    }

    private EvalOracleSnapshot loadFromExternalDirectory(String snapshotName) {
        String directory = properties.getSnapshotDirectory();
        if (directory == null || directory.isBlank()) {
            throw new SnapshotNotFoundException();
        }
        Path base = Path.of(directory).toAbsolutePath().normalize();
        Path file = base.resolve(snapshotName + ".json").normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            throw new SnapshotNotFoundException();
        }
        try (InputStream input = Files.newInputStream(file)) {
            return objectMapper.readValue(input, EvalOracleSnapshot.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to load oracle snapshot: " + snapshotName, ex);
        }
    }

    private EvalOracleSnapshot loadFromClasspath(String snapshotName) {
        ClassPathResource resource = new ClassPathResource("eval/snapshots/" + snapshotName + ".json");
        if (!resource.exists()) {
            throw new SnapshotNotFoundException();
        }
        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readValue(input, EvalOracleSnapshot.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to load oracle snapshot: " + snapshotName, ex);
        }
    }

    public static class SnapshotNotFoundException extends RuntimeException {
    }
}
