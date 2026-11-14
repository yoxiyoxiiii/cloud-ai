package com.cloudai.aggregate;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编码规范守护（自 cloud-task 版裁剪适配：纯读聚合服务域规则）。
 * 源码文本级断言：违反即构建失败并列出文件与行号。零外部依赖。
 * 域特有规则：api 模块镜像字段清单冻结（防镜像漂移，契约 §3）、pom 禁引 inner-starter（§4.3 物理不并存）。
 */
class AggregateArchitectureGuardTest {

    private static final Path MAIN_JAVA = Paths.get("src", "main", "java");

    /** api 模块镜像类位置（守护 cwd = cloud-aggregate，api 工程在 ../cloud-api/） */
    private static final Path BPMN_API_DOMAIN =
            Paths.get("..", "cloud-api", "cloud-bpmn-api", "src", "main", "java", "com", "cloudai", "bpmn", "api", "domain");
    private static final Path SYSTEM_API_DOMAIN =
            Paths.get("..", "cloud-api", "cloud-system-api", "src", "main", "java", "com", "cloudai", "system", "api", "domain");

    /** Controller 两行式：禁止 R.ok( 内联 service 调用 */
    private static final Pattern INLINE_R_OK = Pattern.compile("R\\.ok\\([^;)]*(Service\\.)");

    /** @PathVariable 必须显式命名：禁止裸类型形式 */
    private static final Pattern BARE_PATH_VARIABLE = Pattern.compile("@PathVariable\\s+(Long|String|Integer)");

    /** Controller 映射注解（javadoc 检查的锚点行） */
    private static final Pattern MAPPING_ANNOTATION =
            Pattern.compile("@(Get|Post|Put|Delete|Request)Mapping");

    /** 镜像类字段声明行：private 类型 字段名; */
    private static final Pattern FIELD_DECLARATION = Pattern.compile("^\\s*private\\s+[\\w.]+\\s+(\\w+)\\s*;");

    /** 方法签名行：行首可见性修饰符且含 "("（赋值/控制流/注解行不以可见性修饰符开头） */
    private static final Pattern METHOD_SIGNATURE = Pattern.compile("^\\s*(public|private|protected)\\s+[^=;]*\\(");

    /** 镜像类禁带的下游专用注解（翻译/数据权限/序列化定制都是下游的事，镜像纯转发） */
    private static final Pattern MIRROR_FORBIDDEN_ANNOTATION =
            Pattern.compile("@(TranslateVO|UserTrans|DictTrans|DataPermVo|JsonProperty|TableField)");

    /** 契约 §1.3 冻结的镜像字段清单（下游 VO 真源字段逐字同步处；漂移即契约破坏） */
    private static final Set<String> TASK_VO_FIELDS = Set.of(
            "taskId", "approvalId", "businessType", "businessTypeName", "title",
            "detailPath", "applyUser", "applyUserName", "createTime");

    private static final Set<String> APPROVAL_VO_FIELDS = Set.of(
            "id", "businessType", "businessTypeName", "businessKey", "title", "status", "statusLabel",
            "applyUser", "applyUserName", "approver", "approverName", "detailPath",
            "processInstanceId", "createTime", "updateTime");

    private static final Set<String> NOTIFY_SUMMARY_VO_FIELDS = Set.of("unreadCount", "latestId");

    @Test
    void controller_returns_twoLineStyle_noInlineServiceCall() throws IOException {
        List<String> violations = scan(MAIN_JAVA.resolve("com/cloudai/aggregate/controller"), "*.java", INLINE_R_OK);
        assertThat(violations).as("禁止内联 R.ok(service.xxx(...))，先落局部变量").isEmpty();
    }

    @Test
    void pathVariable_must_have_explicit_name() throws IOException {
        List<String> violations = scan(MAIN_JAVA, "*.java", BARE_PATH_VARIABLE);
        assertThat(violations).as("@PathVariable 必须显式命名（Spring 6.1 隐式命名在 -parameters 缺失时 500）").isEmpty();
    }

    @Test
    void controller_must_not_use_map_type() throws IOException {
        List<String> violations = scan(MAIN_JAVA.resolve("com/cloudai/aggregate/controller"), "*.java",
                Pattern.compile("Map\\s*<"));
        assertThat(violations).as("Controller 入参/返回禁止 Map——一律 DTO（字段可校验、可演进、可文档化）").isEmpty();
    }

    /** 每个映射注解方法上方（跳过其它注解行）必须先遇到 javadoc 结尾 */
    @Test
    void controller_method_must_have_javadoc() throws IOException {
        List<String> violations = new ArrayList<>();
        Path dir = MAIN_JAVA.resolve("com/cloudai/aggregate/controller");
        for (Path file : listFiles(dir, "*.java")) {
            String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\\r?\\n");
            for (int i = 0; i < lines.length; i++) {
                if (!MAPPING_ANNOTATION.matcher(lines[i]).find()) {
                    continue;
                }
                if (!hasJavadocAbove(lines, i)) {
                    violations.add(file.getFileName() + ":" + (i + 1) + " 映射方法缺 javadoc");
                }
            }
        }
        assertThat(violations).as("Controller 方法必须有 javadoc 注释").isEmpty();
    }

    /** 全部枚举声明名必须以 Enum 为后缀（段状态枚举 SectionStatusEnum 同规则） */
    @Test
    void enum_declaration_must_have_enum_suffix() throws IOException {
        List<String> violations = new ArrayList<>();
        Matcher m = Pattern.compile("\\benum\\s+(\\w+)").matcher("");
        for (Path file : listFiles(MAIN_JAVA, "*.java")) {
            m.reset(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                if (!m.group(1).endsWith("Enum")) {
                    violations.add(file.getFileName() + " → enum " + m.group(1));
                }
            }
        }
        assertThat(violations).as("枚举名必须以 Enum 为后缀").isEmpty();
    }

    @Test
    void method_body_max_100_lines() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : listFiles(MAIN_JAVA, "*.java")) {
            String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!METHOD_SIGNATURE.matcher(lines[i]).find()) {
                    continue;
                }
                Integer end = blockEndLine(lines, i);
                if (end != null && end - i + 1 > 100) {
                    violations.add(file + ":" + (i + 1) + " " + lines[i].trim() + " (" + (end - i + 1) + " 行)");
                }
            }
        }
        assertThat(violations).as("方法体 >100 行（硬上限；目标 50 行）——单一职责，超限必须拆分").isEmpty();
    }

    // ---- 镜像字段冻结（契约 §3：提供方改端点出参必须同步镜像，守护在此拦截漂移） ----

    @Test
    void mirror_taskVo_fields_frozen() throws IOException {
        assertThat(extractFieldNames(BPMN_API_DOMAIN.resolve("TaskVo.java")))
                .as("TaskVo 镜像字段须与契约 §1.3 逐字一致（9 字段）")
                .containsExactlyInAnyOrderElementsOf(TASK_VO_FIELDS);
    }

    @Test
    void mirror_approvalVo_fields_frozen() throws IOException {
        assertThat(extractFieldNames(BPMN_API_DOMAIN.resolve("ApprovalVo.java")))
                .as("ApprovalVo 镜像字段须与契约 §1.3 逐字一致（15 字段）")
                .containsExactlyInAnyOrderElementsOf(APPROVAL_VO_FIELDS);
    }

    @Test
    void mirror_notifySummaryVo_fields_frozen() throws IOException {
        assertThat(extractFieldNames(SYSTEM_API_DOMAIN.resolve("NotifySummaryVo.java")))
                .as("NotifySummaryVo 镜像字段须与契约 §1.3 逐字一致（2 字段）")
                .containsExactlyInAnyOrderElementsOf(NOTIFY_SUMMARY_VO_FIELDS);
    }

    /** 镜像类禁带下游专用注解（翻译/数据权限注解是下游职责，镜像纯转发 JSON） */
    @Test
    void mirror_classes_must_not_carry_downstream_annotations() throws IOException {
        List<Path> mirrors = List.of(
                BPMN_API_DOMAIN.resolve("TaskVo.java"),
                BPMN_API_DOMAIN.resolve("ApprovalVo.java"),
                SYSTEM_API_DOMAIN.resolve("NotifySummaryVo.java"));
        List<String> violations = new ArrayList<>();
        for (Path mirror : mirrors) {
            String content = Files.readString(mirror, StandardCharsets.UTF_8);
            Matcher m = MIRROR_FORBIDDEN_ANNOTATION.matcher(content);
            if (m.find()) {
                violations.add(mirror.getFileName() + " → " + m.group());
            }
        }
        assertThat(violations).as("镜像类不带任何下游注解（纯字段类）").isEmpty();
    }

    // ---- 依赖面（设计 §4.3：聚合层与 inner 体系物理不并存） ----

    /** pom 禁引 cloud-common-inner-starter：聚合层只调下游外部面，inner 密钥不进非 inner 通道 */
    @Test
    void pom_must_not_depend_on_inner_starter() throws IOException {
        String pom = Files.readString(Paths.get("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pom.contains("cloud-common-inner-starter"))
                .as("聚合层禁引 cloud-common-inner-starter（出站只有用户头转发拦截器，密钥不泄露给外部面通道）")
                .isFalse();
    }

    // ---- 扫描基础设施 ----

    /** 映射注解行上方：跳过注解行与空白，3 行内遇 javadoc 结尾即合规 */
    private boolean hasJavadocAbove(String[] lines, int idx) {
        for (int j = idx - 1; j >= 0 && j >= idx - 5; j--) {
            String t = lines[j].trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("@")) {
                continue;
            }
            return t.endsWith("*/");
        }
        return false;
    }

    /** 提取镜像类字段名集合（按文件出现顺序去重；非字段行不匹配） */
    private Set<String> extractFieldNames(Path mirror) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (String line : Files.readAllLines(mirror, StandardCharsets.UTF_8)) {
            Matcher m = FIELD_DECLARATION.matcher(line);
            if (m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    /** 从签名行起做大括号深度配对，返回闭合 "}" 所在行号；先遇 ";"（无方法体）或到文件尾返回 null */
    private Integer blockEndLine(String[] lines, int signatureIdx) {
        int depth = 0;
        boolean opened = false;
        for (int i = signatureIdx; i < lines.length; i++) {
            for (int c = 0; c < lines[i].length(); c++) {
                char ch = lines[i].charAt(c);
                if (ch == '{') {
                    depth++;
                    opened = true;
                } else if (ch == '}') {
                    depth--;
                    if (opened && depth == 0) {
                        return i;
                    }
                } else if (ch == ';' && !opened) {
                    return null;
                }
            }
        }
        return null;
    }

    private List<String> scan(Path root, String glob, Pattern forbidden) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : listFiles(root, glob)) {
            String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (forbidden.matcher(lines[i]).find()) {
                    violations.add(file + ":" + (i + 1) + " → " + lines[i].trim());
                }
            }
        }
        return violations;
    }

    private List<Path> listFiles(Path root, String... globs) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.exists(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> all = walk.filter(Files::isRegularFile).toList();
            for (Path p : all) {
                for (String glob : globs) {
                    if (glob.startsWith("*")) {
                        if (p.getFileName().toString().endsWith(glob.substring(1))) {
                            files.add(p);
                            break;
                        }
                    } else if (p.getFileName().toString().equals(glob)) {
                        files.add(p);
                        break;
                    }
                }
            }
        }
        return files;
    }
}
