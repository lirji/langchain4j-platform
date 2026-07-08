package com.lrj.platform.conversation.extract;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code POST /extract?type=ticket}：自由文本 → 结构化 POJO（langchain4j structured output）。
 * 用抽取器注册表按 {@code type} 分派，未知类型 → 400。端点常开（无 feature flag）。
 *
 * <p>泛化单体的类型专用抽取：新增目标类型只需注册一个 {@code type → Function<String,Object>}。
 */
@RestController
public class ExtractController {

    private final Map<String, Function<String, Object>> registry = new LinkedHashMap<>();

    public ExtractController(Extractor extractor) {
        registry.put("ticket", extractor::extractTicket);
    }

    @PostMapping("/extract")
    public Object extract(@RequestParam(value = "type", defaultValue = "ticket") String type,
                          @RequestBody Map<String, String> body) {
        Function<String, Object> fn = registry.get(type);
        if (fn == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unknown extract type: " + type + "; supported: " + registry.keySet());
        }
        return fn.apply(body.getOrDefault("text", ""));
    }
}
