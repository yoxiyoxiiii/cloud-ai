package com.cloudai.system.util;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板占位符解析器（通知中心设计 §3）：纯函数、无状态。
 * 占位符语法 ${var}，变量名字母开头、后接字母数字下划线；正则提取 + 逐个替换，不引模板引擎。
 * 变量集合的单一事实源 = 模板内容本身（title_template + content_template 提取，LinkedHashSet 保序）；
 * variables_desc 只是给人看的说明文本，不参与解析。
 */
public final class NotifyTemplateParser {

    private NotifyTemplateParser() {
    }

    /** 变量名字母开头、后接字母数字下划线（契约 §2.1 variables 键校验同规则） */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z][a-zA-Z0-9_]*)\\}");

    /** 提取标题+内容两段模板的变量名全集（LinkedHashSet 保序：缺失变量列表顺序稳定可断言） */
    public static Set<String> extractVariables(String title, String content) {
        Set<String> variables = new LinkedHashSet<>();
        collect(title, variables);
        collect(content, variables);
        return variables;
    }

    /**
     * 渲染：逐个占位符替换为变量值。多余变量忽略（模板后续加变量不破坏既有调用方，前向兼容）；
     * 变量缺失时保留原文占位符（调用方发送前已做 3045 缺失校验，此处兜底不抛错保持纯函数语义）。
     */
    public static String render(String text, Map<String, String> variables) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String value = variables.get(matcher.group(1));
            // quoteReplacement：变量值含 $ 或 \ 时防 appendReplacement 当作引用符误解析
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value == null ? matcher.group(0) : value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static void collect(String text, Set<String> variables) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
    }
}
