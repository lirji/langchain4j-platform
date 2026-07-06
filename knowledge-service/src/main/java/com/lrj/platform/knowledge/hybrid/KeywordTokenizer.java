package com.lrj.platform.knowledge.hybrid;

import java.util.Set;

public interface KeywordTokenizer {

    Set<String> tokenize(String text);
}
