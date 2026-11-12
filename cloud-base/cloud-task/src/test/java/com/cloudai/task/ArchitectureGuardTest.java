package com.cloudai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编码规范守护（自 cloud-system 版裁剪适配：任务中心域规则 + 流水表口径）。
 * 源码文本级断言：违反即构建失败并列出文件与行号。零外部依赖。
 */
class ArchitectureGuardTest {

    private static final Path MAIN_JAVA = Paths.get("src", "main", "java");
    private static final Path MAIN_MAPPER_XML = Paths.get("src", "main", "resources", "mapper");

    /** Controller 两行式：禁止 R.ok( 内联 service 调用 */
    private static final Pattern INLINE_R_OK = Pattern.compile("R\\.ok\\([^;)]*(Service\\.|Registry\\()");

    /** @PathVariable 必须显式命名：禁止裸类型形式 */
    private static final Pattern BARE_PATH_VARIABLE = Pattern.compile("@PathVariable\\s+(Long|String|Integer)");

    /** mapper XML 单行 <if>：开标签后同行还有内容即违规（[^<\\r\\n] 兼容 CRLF 工作副本） */
    private static final Pattern SINGLE_LINE_IF = Pattern.compile("<if\\s+test=\"[^\"]*\">[^<\\r\\n]");

    /** DDL 列定义行：缩进 + 列名 + 类型（PRIMARY/UNIQUE/KEY 等约束行不匹配） */
    private static final Pattern DDL_COLUMN_LINE =
            Pattern.compile("^\\s+\\w+\\s+(BIGINT|VARCHAR|TINYINT|INT|CHAR|DATETIME|DATE)\\b");

    /** 方法签名行：行首可见性修饰符且含 "("（赋值/控制流/注解行不以可见性修饰符开头） */
    private static final Pattern METHOD_SIGNATURE = Pattern.compile("^\\s*(public|private|protected)\\s+[^=;]*\\(");

    /** 建库脚本位于聚合根 scripts/（模块构建 cwd = cloud-task） */
    private static final Path DDL_SQL = Paths.get("..", "scripts", "sql", "cloud_task.sql");

    @Test
    void controller_returns_twoLineStyle_noInlineServiceCall() throws IOException {
        List<String> violations = scan(MAIN_JAVA.resolve("com/cloudai/task/controller"), "*.java",
                INLINE_R_OK);
        assertThat(violations).as("禁止内联 R.ok(service.xxx(...))，先落局部变量").isEmpty();
    }

    @Test
    void pathVariable_must_have_explicit_name() throws IOException {
        List<String> violations = scan(MAIN_JAVA, "*.java", BARE_PATH_VARIABLE);
        assertThat(violations).as("@PathVariable 必须显式命名（Spring 6.1 隐式命名在 -parameters 缺失时 500）").isEmpty();
    }

    @Test
    void service_layer_must_not_use_mybatisPlus_wrapper() throws IOException {
        List<String> violations = scan(MAIN_JAVA.resolve("com/cloudai/task/service"), "*.java",
                Pattern.compile("com\\.baomidou\\.mybatisplus\\.core\\.conditions"));
        assertThat(violations).as("Service 层禁止 Wrapper——SQL 一律下沉 mapper XML").isEmpty();
    }

    @Test
    void mapper_interface_must_not_extend_baseMapper() throws IOException {
        List<String> violations = scan(MAIN_JAVA.resolve("com/cloudai/task/mapper"), "*.java",
                Pattern.compile("extends\\s+BaseMapper"));
        assertThat(violations).as("Mapper 接口禁止 extends BaseMapper（手写 XML，语句与接口一一同绑定）").isEmpty();
    }

    @Test
    void controller_must_not_depend_on_mapper_layer() throws IOException {
        List<String> violations = scan(MAIN_JAVA.resolve("com/cloudai/task/controller"), "*.java",
                Pattern.compile("import\\s+com\\.cloudai\\.task\\.mapper\\."));
        assertThat(violations).as("Controller 禁止 import mapper——分层依赖 Controller → Service → Mapper").isEmpty();
    }

    @Test
    void mapper_xml_must_not_use_dollar_placeholder() throws IOException {
        List<String> violations = scan(MAIN_MAPPER_XML, "*.xml",
                Pattern.compile(Pattern.quote("${")));
        assertThat(violations).as("mapper XML 禁止 ${}（SQL 注入面），一律 #{}").isEmpty();
    }

    /**
     * 流水表口径（与 cloud-system 主表规则相反的刻意形态）：async_task 无 deleted 列
     * （任务行无软删语义，终态行到期物理清理，设计 2026-11-10-task-center-design §4）。
     * 任何语句出现 deleted 都是对流水表口径的破坏（手滑套主表模板），构建期拦截；
     * XML 注释剥离后判定（头注记档文字本身含该词属合法）。
     */
    @Test
    void flow_table_sql_must_not_reference_deleted() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path xml : listFiles(MAIN_MAPPER_XML, "*.xml")) {
            String sql = Files.readString(xml, StandardCharsets.UTF_8)
                    .replaceAll("(?s)<!--.*?-->", "");
            if (sql.contains("deleted")) {
                violations.add(xml.getFileName() + " (flow table async_task has no deleted column)");
            }
        }
        assertThat(violations).as("async_task 为流水表无 deleted 列——语句出现 deleted 即口径破坏").isEmpty();
    }

    @Test
    void mapper_xml_if_tag_body_must_be_multiline() throws IOException {
        List<String> violations = scan(MAIN_MAPPER_XML, "*.xml", SINGLE_LINE_IF);
        assertThat(violations).as("mapper XML 禁单行 <if test=\"...\">content</if>：标签体必须换行（git diff/评审可见性）")
                .isEmpty();
    }

    @Test
    void controller_must_not_use_map_type() throws IOException {
        List<String> violations = scan(MAIN_JAVA.resolve("com/cloudai/task/controller"), "*.java",
                Pattern.compile("Map\\s*<"));
        assertThat(violations).as("Controller 入参/返回禁止 Map——一律 DTO（字段可校验、可演进、可文档化）").isEmpty();
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

    @Test
    void sql_ddl_every_column_commented() throws IOException {
        List<String> violations = new ArrayList<>();
        if (Files.exists(DDL_SQL)) {
            boolean inCreateTable = false;
            String table = "";
            int lineNo = 0;
            for (String line : Files.readAllLines(DDL_SQL, StandardCharsets.UTF_8)) {
                lineNo++;
                if (line.startsWith("CREATE TABLE")) {
                    inCreateTable = true;
                    table = line.replaceFirst("CREATE TABLE\\s+(\\w+).*", "$1");
                } else if (inCreateTable && line.startsWith(")")) {
                    inCreateTable = false;
                } else if (inCreateTable && DDL_COLUMN_LINE.matcher(line).find() && !line.contains("COMMENT")) {
                    violations.add("cloud_task.sql:" + lineNo + " " + table + " → " + line.trim());
                }
            }
        }
        assertThat(violations).as("DDL 每列必须有 COMMENT（含审计列）").isEmpty();
    }

    @Test
    void no_multiple_field_value_injection() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : listFiles(MAIN_JAVA, "*.java")) {
            long count = Files.readString(file, StandardCharsets.UTF_8).lines()
                    .filter(line -> line.contains("@Value(")).count();
            if (count >= 2) {
                violations.add(file + " → " + count + " 处 @Value");
            }
        }
        assertThat(violations).as("同文件 >=2 个 @Value：同前缀多值须封装 @ConfigurationProperties 对象").isEmpty();
    }

    // ---- 方法命名动词集（任务域扩展）/ VO 隔离 / 禁三方拷贝 ----

    /** 方法声明行：public 可选（接口方法隐式 public），捕获组=方法名 */
    private static final Pattern DECLARED_METHOD =
            Pattern.compile("^\\s*(?:public\\s+)?(?:\\w[\\w<>,\\s\\[\\]]*?)\\s+(\\w+)\\s*\\(");

    /**
     * 命名白名单前缀：CRUD 动词集 + 任务域动词 submit/retry/requeue/claim/flag
     * （提交/手动重试/回队重跑/CAS 认领/协作取消标记——引擎状态机核心入口，语义化优先）
     * 与告警域动词 alert（失败告警链）；getter/setter（get/set/is）照例放行。
     */
    private static final Pattern NAMING_PREFIX_OK =
            Pattern.compile("^(find|save|update|pageList|list|delete|count|cancel|retry|submit"
                    + "|requeue|claim|flag|alert|get|set|is)\\w*");

    /** 语句起始关键字（throw new Xxx( / return foo( 会被误判为声明，前置排除） */
    private static final List<String> STATEMENT_KEYWORDS = List.of(
            "throw", "return", "new", "if", "else", "for", "while", "switch", "catch", "do", "try");

    /** Controller 实体泛型返回：R<AsyncTask> / R<PageResult<AsyncTask>>（Vo 后缀不匹配） */
    private static final Pattern R_OF_ENTITY =
            Pattern.compile("R<\\s*((?:PageResult|List)\\s*<\\s*)?AsyncTask\\s*>");

    /** 三方 Bean 拷贝工具 import（实体→VO 转换一律 convert 包原生 setter） */
    private static final Pattern THIRD_PARTY_BEAN_COPY =
            Pattern.compile("import\\s+[^;]*(?i:beanutils|mapstruct|modelmapper|dozer)");

    @Test
    void method_naming_prefix_whitelist() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path dir : List.of(MAIN_JAVA.resolve("com/cloudai/task/service"),
                MAIN_JAVA.resolve("com/cloudai/task/mapper"))) {
            for (Path file : listFiles(dir, "*.java")) {
                String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\\r?\\n");
                for (int i = 0; i < lines.length; i++) {
                    String name = extractMethodName(lines, i);
                    if (name == null || NAMING_PREFIX_OK.matcher(name).matches() || isOverride(lines, i)) {
                        continue;
                    }
                    violations.add(file + ":" + (i + 1) + " " + name + "（前缀不在动词集白名单）");
                }
            }
        }
        assertThat(violations).as("Service/Mapper 方法命名须命中任务域动词集前缀").isEmpty();
    }

    @Test
    void controller_returns_vo_only() throws IOException {
        List<String> violations = scan(MAIN_JAVA.resolve("com/cloudai/task/controller"), "*.java",
                R_OF_ENTITY);
        assertThat(violations).as("Controller 禁 DB 实体直出（R<AsyncTask> 等），一律 XxxVo").isEmpty();
    }

    @Test
    void no_third_party_bean_copy() throws IOException {
        List<String> violations = scan(MAIN_JAVA, "*.java", THIRD_PARTY_BEAN_COPY);
        assertThat(violations).as("禁 BeanUtils/mapstruct/ModelMapper/dozer 拷贝——convert 包原生 setter 逐字段").isEmpty();
    }

    @Test
    void entity_nested_enum_must_have_enum_suffix() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : listFiles(MAIN_JAVA.resolve("com/cloudai/task/entity"), "*.java")) {
            Matcher m = Pattern.compile("\\benum\\s+(\\w+)")
                    .matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                if (!m.group(1).endsWith("Enum")) {
                    violations.add(file.getFileName() + " → enum " + m.group(1));
                }
            }
        }
        assertThat(violations).as("实体内嵌枚举必须以 Enum 为后缀（如 StatusEnum）").isEmpty();
    }

    // ---- 任务中心域规则（设计 2026-11-10-task-center-design §5） ----

    /** @TaskHandler 注解使用处（带括号；注解声明本身是 @interface 无括号）：必须落 handler/ 包且值非空 */
    @Test
    void taskHandler_classes_must_live_in_handler_package_with_type() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path java : listFiles(MAIN_JAVA, "*.java")) {
            String content = Files.readString(java, StandardCharsets.UTF_8);
            if (!content.contains("@TaskHandler(")) {
                continue;
            }
            if (!java.toString().replace('\\', '/').contains("/handler/")) {
                violations.add(java.getFileName() + " (@TaskHandler outside handler/ package)");
            }
            // 空值注解（@TaskHandler() 或 @TaskHandler）= 类型码缺失，注册表运行期才炸——构建期拦截
            if (Pattern.compile("@TaskHandler\\(\\s*\\)").matcher(content).find()) {
                violations.add(java.getFileName() + " (@TaskHandler value empty)");
            }
        }
        assertThat(violations).as("@TaskHandler 任务类必须落 handler/ 包且类型码非空").isEmpty();
    }

    /** 轮询线程是 SmartLifecycle 自管线程——@Scheduled 全禁（设计 §5.2，装配/生命周期归引擎自管） */
    @Test
    void scheduled_annotation_is_forbidden() throws IOException {
        List<String> violations = scan(MAIN_JAVA, "*.java", Pattern.compile("@Scheduled"));
        assertThat(violations).as("任务中心禁 @Scheduled——调度面统一 xxl-job，pickup 轮询为 SmartLifecycle 自管线程").isEmpty();
    }

    /** 服务模块禁自建 executor：XxlJobSpringExecutor 只能来自 cloud-common-xxljob-starter 装配 */
    @Test
    void service_module_must_not_new_xxl_executor() throws IOException {
        List<String> violations = scan(MAIN_JAVA, "*.java",
                Pattern.compile("new\\s+XxlJobSpringExecutor"));
        assertThat(violations).as("服务模块禁止 new XxlJobSpringExecutor——executor 装配归 cloud-common-xxljob-starter").isEmpty();
    }

    @Test
    void xxljob_handler_class_must_live_in_job_package() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path java : listFiles(MAIN_JAVA, "*.java")) {
            String content = Files.readString(java, StandardCharsets.UTF_8);
            if (content.contains("@XxlJob(") && !java.toString().replace('\\', '/').contains("/job/")) {
                violations.add(java.getFileName() + " (@XxlJob outside job/ package)");
            }
        }
        assertThat(violations).as("@XxlJob 任务类必须落 job/ 包").isEmpty();
    }

    /** 本服务是 /inner 面提供方（/inner/task/submit）：必须依赖 cloud-common-inner-starter
     * 才有准入过滤器——提供方漏依赖 = /inner 静默裸奔，构建期红。 */
    @Test
    void pom_must_depend_on_inner_starter() throws IOException {
        String pom = Files.readString(Paths.get("pom.xml"), StandardCharsets.UTF_8);
        assertThat(Pattern.compile(
                        "<dependency>\\s*<groupId>com\\.cloudai</groupId>\\s*"
                                + "<artifactId>cloud-common-inner-starter</artifactId>\\s*</dependency>",
                        Pattern.DOTALL).matcher(pom).find())
                .as("cloud-task pom 必须依赖 cloud-common-inner-starter（/inner/task 面准入）")
                .isTrue();
    }

    // ---- 扫描基础设施 ----

    /** 提取第 idx 行的方法声明名；注释/注解/private/protected/语句关键字行返回 null */
    private String extractMethodName(String[] lines, int idx) {
        String line = lines[idx];
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("*") || trimmed.startsWith("//")
                || trimmed.startsWith("/*") || trimmed.startsWith("@")) {
            return null;
        }
        if (line.matches("\\s*(private|protected)\\s.*")) {
            return null;
        }
        String first = trimmed.split("[^A-Za-z]+", 2)[0];
        if (STATEMENT_KEYWORDS.contains(first)) {
            return null;
        }
        Matcher m = DECLARED_METHOD.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    /** 签名行上方紧邻注解是否为 @Override（Object 方法豁免） */
    private boolean isOverride(String[] lines, int idx) {
        for (int j = idx - 1; j >= 0 && j >= idx - 3; j--) {
            String t = lines[j].trim();
            if (t.isEmpty()) {
                continue;
            }
            return t.equals("@Override");
        }
        return false;
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
