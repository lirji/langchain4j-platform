package com.lrj.platform.agent.actions;

import com.lrj.platform.agent.AgentAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class CurrentTimeAction implements AgentAction {

    @Override
    public String name() {
        return "current_time";
    }

    @Override
    public String description() {
        return "查询当前时间；actionInput 填 IANA 时区（如 Asia/Shanghai），留空则用系统默认时区";
    }

    @Override
    public String run(String input) {
        ZoneId zone;
        try {
            zone = input == null || input.isBlank() ? ZoneId.systemDefault() : ZoneId.of(input.trim());
        } catch (Exception ex) {
            return "无法识别的时区 '" + input + "'，请用 IANA 格式（如 Asia/Shanghai / UTC）";
        }
        return ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " (" + zone + ")";
    }
}
