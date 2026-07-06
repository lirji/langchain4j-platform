package com.lrj.platform.knowledge.hybrid;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SimpleKeywordTokenizer implements KeywordTokenizer {

    private static final Pattern TOKEN = Pattern.compile("[\\p{IsAlphabetic}\\p{IsDigit}]+|[\\p{IsHan}]+");
    private static final Pattern HAN = Pattern.compile("\\p{IsHan}+");

    @Override
    public Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new HashSet<>();
        Matcher matcher = TOKEN.matcher(text.toLowerCase());
        while (matcher.find()) {
            String token = matcher.group();
            if (HAN.matcher(token).matches()) {
                addChineseTokens(tokens, token);
            } else if (token.length() > 1) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static void addChineseTokens(Set<String> tokens, String token) {
        tokens.add(token);
        if (token.length() == 1) {
            return;
        }
        for (int i = 0; i < token.length() - 1; i++) {
            tokens.add(token.substring(i, i + 2));
        }
    }
}
