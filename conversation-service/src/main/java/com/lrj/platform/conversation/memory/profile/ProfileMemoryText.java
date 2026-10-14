package com.lrj.platform.conversation.memory.profile;

/** 内存与 JDBC 共用既有文本去重规则，避免切换存储改变画像抽取结果。 */
final class ProfileMemoryText {
    private ProfileMemoryText() {
    }

    static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。、；：！？（）【】「」]", "");
    }

    static boolean overlaps(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        return a.contains(b) || b.contains(a);
    }
}
