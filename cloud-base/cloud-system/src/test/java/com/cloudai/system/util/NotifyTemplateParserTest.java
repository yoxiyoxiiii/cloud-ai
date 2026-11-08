package com.cloudai.system.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模板解析器单测（通知中心设计 §3 纯函数）：提取（保序/去重/非法名忽略）/ 替换（多余忽略、缺失保留原文、
 * 变量值特殊字符与嵌套占位符不二次展开）。
 */
class NotifyTemplateParserTest {

    @Test
    void extract_noPlaceholdersReturnsEmpty() {
        assertThat(NotifyTemplateParser.extractVariables("plain title", "plain content")).isEmpty();
    }

    @Test
    void extract_collectsFromTitleAndContentInOrder() {
        // LinkedHashSet 保序：先 title 后 content，缺失变量列表顺序稳定
        Set<String> vars = NotifyTemplateParser.extractVariables(
                "hello ${user} at ${place}", "dear ${user}, ${action} now");
        assertThat(vars).containsExactly("user", "place", "action");
    }

    @Test
    void extract_deduplicatesAcrossTitleAndContent() {
        assertThat(NotifyTemplateParser.extractVariables("${a} ${b}", "${b} ${a}"))
                .containsExactly("a", "b");
    }

    @Test
    void extract_ignoresInvalidVariableNames() {
        // 变量名必须字母开头、后接字母数字下划线：数字开头/含连字符/中文/裸 $ 均不提取
        Set<String> vars = NotifyTemplateParser.extractVariables(
                "${1abc} ${a-b} ${中文} $notvar {brace}", "${ok_one}");
        assertThat(vars).containsExactly("ok_one");
    }

    @Test
    void extract_adjacentPlaceholdersAllCollected() {
        assertThat(NotifyTemplateParser.extractVariables("${a}${b}${c}", ""))
                .containsExactly("a", "b", "c");
    }

    @Test
    void render_replacesAllPlaceholders() {
        String rendered = NotifyTemplateParser.render(
                "hello ${user} at ${place}", Map.of("user", "tom", "place", "office"));
        assertThat(rendered).isEqualTo("hello tom at office");
    }

    @Test
    void render_extraVariablesIgnored() {
        // 前向兼容：模板后续加变量不破坏既有调用方，多余键无占位符可命中自然忽略
        String rendered = NotifyTemplateParser.render("hello ${user}",
                Map.of("user", "tom", "unused", "x"));
        assertThat(rendered).isEqualTo("hello tom");
    }

    @Test
    void render_missingVariableKeepsLiteralPlaceholder() {
        // 纯函数兜底语义：缺失保留原文（发送路径在 Service 已做 3045 缺失前置校验，不到达此态）
        String rendered = NotifyTemplateParser.render("hello ${user}", Map.of());
        assertThat(rendered).isEqualTo("hello ${user}");
    }

    @Test
    void render_valueWithRegexSpecialCharsIsLiteral() {
        // 变量值含 $ 与 \：quoteReplacement 防当引用符误解析
        String rendered = NotifyTemplateParser.render("cost ${amount}", Map.of("amount", "$100\\50"));
        assertThat(rendered).isEqualTo("cost $100\\50");
    }

    @Test
    void render_placeholderInValueNotReExpanded() {
        // 单趟替换：值本身形如占位符不二次展开（防注入递归）
        String rendered = NotifyTemplateParser.render("hi ${user}", Map.of("user", "${other}"));
        assertThat(rendered).isEqualTo("hi ${other}");
    }

    @Test
    void render_emptyAndNullTextHandled() {
        assertThat(NotifyTemplateParser.render(null, Map.of())).isEmpty();
        assertThat(NotifyTemplateParser.render("", Map.of("a", "x"))).isEmpty();
    }

    @Test
    void render_emptyValueReplacesToEmpty() {
        assertThat(NotifyTemplateParser.render("[${a}]", Map.of("a", ""))).isEqualTo("[]");
    }

    @Test
    void extract_nullSegmentsHandled() {
        assertThat(NotifyTemplateParser.extractVariables(null, "${a}")).containsExactly("a");
        assertThat(NotifyTemplateParser.extractVariables(null, null)).isEmpty();
    }

    @Test
    void render_longValueFullLength() {
        // 超长判定在 Service（3046），解析器只负责完整替换不截断
        String longValue = "x".repeat(1200);
        String rendered = NotifyTemplateParser.render("c=${v}", Map.of("v", longValue));
        assertThat(rendered).isEqualTo("c=" + longValue);
        assertThat(List.of(rendered.length())).contains(1202);
    }
}
