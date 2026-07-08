package com.lrj.platform.conversation.guardrail;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 高置信度 prompt-injection 规则集（移植单体 {@code PromptInjectionRules}）。原则：只挑明显攻击，宁缺毋滥。
 * 参考 OWASP LLM01:2025 分 4 类：指令劫持 / 系统提示提取 / 角色扮演越狱 / 特殊 token 注入。
 *
 * <p>first-hit 终止：返回第一条命中的 pattern 名，调用方据此 block / sanitize / audit。
 * 中文按字面匹配（无大小写概念）。
 */
public final class PromptInjectionRules {

    private PromptInjectionRules() {
    }

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final List<Rule> RULES = List.of(
            // 1. instruction hijacking
            new Rule("hijack-ignore-prev-en",
                    Pattern.compile("\\bignore\\s+(the\\s+|all\\s+|any\\s+|previous|prior|above)\\s*(instructions?|prompts?|rules?|messages?)\\b", FLAGS)),
            new Rule("hijack-disregard-en",
                    Pattern.compile("\\bdisregard\\s+(the\\s+|all\\s+|previous|prior|above)\\s*(instructions?|prompts?|rules?)\\b", FLAGS)),
            new Rule("hijack-override-en",
                    Pattern.compile("\\b(override|bypass|forget)\\s+(your\\s+|all\\s+)?(safety|guidelines?|policy|policies|restrictions?|rules?|instructions?)\\b", FLAGS)),
            new Rule("hijack-ignore-prev-zh",
                    Pattern.compile("(忽略|无视|忘记|忘掉|抛弃)(之前|以前|上面|前面|所有|全部)(的)?(指令|提示|规则|要求|prompt|消息|对话)", FLAGS)),
            new Rule("hijack-new-system-zh",
                    Pattern.compile("(现在|从现在开始|本轮|接下来)(请)?(扮演|你是|你扮演|假装是)\\s*[^，。\\s]{2,30}", FLAGS)),

            // 2. system-prompt extraction
            new Rule("extract-system-prompt-en",
                    Pattern.compile("\\b(repeat|show|reveal|print|display|output|tell\\s+me|what\\s+(is|was|are|were))\\b.{0,40}\\b(system\\s+prompt|initial\\s+instructions?|original\\s+instructions?|system\\s+message|setup\\s+prompt|hidden\\s+prompt)\\b", FLAGS)),
            new Rule("extract-system-prompt-zh",
                    Pattern.compile("(显示|输出|打印|告诉我|展示|背诵|重复|是什么|有什么|告知).{0,40}(系统提示|系统消息|初始指令|原始指令|你的设定|你的指令|你的角色设定|system\\s*prompt|prompt|instruction)", FLAGS)),
            new Rule("extract-first-words",
                    Pattern.compile("\\b(repeat|print)\\b.{0,30}\\b(first|original)\\b.{0,30}\\b(words?|lines?|messages?)\\b", FLAGS)),

            // 3. role-play jailbreak
            new Rule("jailbreak-dan",
                    Pattern.compile("\\b(DAN|do\\s+anything\\s+now|developer\\s+mode|god\\s+mode|jailbreak)\\b", FLAGS)),
            new Rule("jailbreak-unfiltered",
                    Pattern.compile("\\b(act|pretend|behave|respond)\\s+as\\s+(an?\\s+)?(unrestricted|unfiltered|uncensored|evil|malicious|amoral)\\b", FLAGS)),
            new Rule("jailbreak-zh",
                    Pattern.compile("(越狱|开发者模式|不受限制|无限制模式|无道德|无审查|绕过限制|绕过审核|越过限制)", FLAGS)),

            // 4. special-token injection
            new Rule("special-token",
                    Pattern.compile("(<\\|im_start\\|>|<\\|im_end\\|>|<\\|system\\|>|<\\|user\\|>|<\\|assistant\\|>|\\[INST\\]|\\[/INST\\]|<<SYS>>)", FLAGS))
    );

    /** 返回第一条命中的 pattern 名；没命中返回 null。 */
    public static String firstMatch(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        for (Rule r : RULES) {
            if (r.pattern.matcher(input).find()) {
                return r.name;
            }
        }
        return null;
    }

    /** 去掉命中的特殊控制 token（sanitize 档最小净化：剥离最危险的 chat-template 注入）。 */
    public static String stripControlTokens(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input.replaceAll("(<\\|im_start\\|>|<\\|im_end\\|>|<\\|system\\|>|<\\|user\\|>|<\\|assistant\\|>|\\[INST\\]|\\[/INST\\]|<<SYS>>)", " ");
    }

    private record Rule(String name, Pattern pattern) {
    }
}
