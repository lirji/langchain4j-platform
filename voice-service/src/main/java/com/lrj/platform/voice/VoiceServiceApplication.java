package com.lrj.platform.voice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * voice-service（{@code /voice/**} 语音闭环 ASR→对话→TTS）的 Spring Boot 启动入口，默认监听 :8091。
 */
@SpringBootApplication
public class VoiceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoiceServiceApplication.class, args);
    }
}
