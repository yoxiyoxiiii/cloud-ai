package com.cloudai.system;

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
 * 编码规范守护（自 cloud-system 沉淀，规则清单见 CLAUDE.md"编码规范"）。
 * 源码文本级断言：违反即构建失败并列出文件与行号。零外部依赖。
 */
class ArchitectureGuardTest {

    private static final Path MAIN_JAVA = Paths.get("src", "main", "java");
    private static final Path MAIN_MAPPER_XML = Paths.get("src", "main", "resources", "mapper");

    /** Controller 两行式：禁止 R.ok( 内联 service 调用/工具构建 */
    private static final Pattern INLINE_R_OK = Pattern.compile("R\\.ok\\([^;)]*(Service\\.|MenuTreeBuilder\\()");

    /** @PathVariable 必须显式命名：禁止裸类型形式 */
    private static final Pattern BARE_PATH_VARIABLE = Pattern.compile("@PathVariable\\s+(Long|String|Integer)");

    /** mapper XML 单行 <if>：开标签后同行还有内容即违规（[^<\\r\\n] 兼容 CRLF 工作副本） */
    private static final Pattern SINGLE_LINE_IF = Pattern.compile("<if\\s+test=\"[^\"]*\">[^<\\r\\n]");

    /** DDL 列定义行：缩进 + 列名 + 类型（PRIMARY/UNIQUE/KEY 等约束行不以类型关键字结尾开头，不匹配）；
     *  DATE 在 DATETIME 之后（交替顺序 + \b 回溯，sys_leave 的 start_date/end_date 日期列） */
    private static final Pattern DDL_COLUMN_LINE =
            Pattern.compile("^\\s+\\w+\\s+(BIGINT|VARCHAR|TINYINT|INT|CHAR|DATETIME|DATE)\\b");

    /** 方法签名行：行首可见性修饰符且含 "("（赋值/控制流/注解行不以可见性修饰符开头） */
    private static final Pattern METHOD_SIGNATURE = Pattern.compile("^\\s*(public|private|protected)\\s+[^=;]*\\(");

    /** 建库脚本位于聚合根 scripts/（模块构建 cwd = cloud-system） */
    private static final Path DDL_SQL = Paths.get("..", "scripts", "sql", "cloud_system.sql");

    @Test
    void controller_returns_twoLineStyle_noInlineServiceCall() throws IOException {
        List<String> violations = scan(MAIN_JAVA.resolve("com/cloudai/system/controller"), "*.java",
                INLINE_R_OK);
        assertThat(violations).as("禁止内联 R.ok(service.xxx(...))/R.ok(Builder.build(...))，先落局部变量").isEmpty();
    }

    @Test
    void pathVariable_must_have_explicit_name() throws IOException {
        List<String> violations = scan(MAIN_JAVA, "*.java", BARE_PATH_VARIABLE);
        assertThat(violations).as("@PathVariable 必须显式命名（Spring 6.1 隐式命名在 -parameters 缺失时 500）").isEmpty();
    }

    @Test
    void service_layer_must_not_use_mybatisPlus_wrapper() throws IOException {
        List<String> violations = scan(MAIN_JAVA.resolve("com/cloudai/system/service"), "*.java",
                Pattern.compile("com\\.baomidou\\.mybatisplus\\.core\\.conditions"));
        assertThat(violations).as("Service 层禁止 Wrapper——SQL 一律下沉 mapper XML").isEmpty();
    }

    @Test
    void mapper_interface_must_not_extend_baseMapper() throws IOException {
        List<String> violations = scan(MAIN_JAVA.resolve("com/cloudai/system/mapper"), "*.java",
                Pattern.compile("extends\\s+BaseMapper"));
        assertThat(violations).as("Mapper 接口禁止 extends BaseMapper（手写 XML，语句与接口一一同绑定）").isEmpty();
    }

    @Test
    void controller_must_not_depend_on_mapper_layer() throws IOException {
        List<String> violations = scan(MAIN_JAVA.resolve("com/cloudai/system/controller"), "*.java",
                Pattern.compile("import\\s+com\\.cloudai\\.system\\.mapper\\."));
        assertThat(violations).as("Controller 禁止 import mapper——分层依赖 Controller → Service → Mapper").isEmpty();
    }

    @Test
    void mapper_xml_must_not_use_dollar_placeholder() throws IOException {
        List<String> violations = scan(MAIN_MAPPER_XML, "*.xml",
                Pattern.compile(Pattern.quote("${")));
        assertThat(violations).as("mapper XML 禁止 ${}（SQL 注入面），一律 #{}").isEmpty();
    }

    @Test
    void master_table_sql_must_handle_deleted() throws IOException {
        // 主表（带逻辑删除列）的每条 select/update 语句必须出现 deleted。
        // 词边界匹配：sys_role_menu 不算 sys_role/sys_menu（关系表无逻辑删除）；
        // sys_dept 随数据权限轮（2026-10-10）纳入主表集；sys_data_perm_* 物理删表无 deleted 列不在此列（设计 D9）
        Pattern masterTable = Pattern.compile(
                "\\bsys_user\\b|\\bsys_role\\b|\\bsys_menu\\b|\\bsys_leave\\b|\\bsys_dept\\b");
        List<String> violations = new ArrayList<>();
        for (Path xml : listFiles(MAIN_MAPPER_XML, "*.xml")) {
            String content = Files.readString(xml, StandardCharsets.UTF_8);
            Matcher block = Pattern.compile("<(select|update)\\b[^>]*id=\"([^\"]+)\"(.*?)</\\1>", Pattern.DOTALL)
                    .matcher(content);
            while (block.find()) {
                String id = block.group(2);
                String body = block.group(3);
                if (masterTable.matcher(body).find() && !body.contains("deleted")) {
                    violations.add(xml.getFileName() + "#" + id + " (master table without deleted)");
                }
            }
        }
        assertThat(violations).as("master table (sys_user/sys_role/sys_menu/sys_leave/sys_dept) SQL must have explicit deleted").isEmpty();
    }

    @Test
    void relation_table_xml_must_be_physical_delete() throws IOException {
        // 纯关系表 SQL 不得出现 deleted（无逻辑删除列）；剥离 XML 注释后判定
        List<String> violations = new ArrayList<>();
        for (Path xml : listFiles(MAIN_MAPPER_XML, "SysUserRoleMapper.xml", "SysRoleMenuMapper.xml")) {
            String sql = Files.readString(xml, StandardCharsets.UTF_8)
                    .replaceAll("<!--.*?-->", "");
            if (sql.contains("deleted")) {
                violations.add(xml.getFileName() + " (relation table has no deleted column)");
            }
        }
        assertThat(violations).isEmpty();
    }

    // ---- 2026-10-05 通用约束（CLAUDE.md"通用约束"6 条之机械可判部分） ----

    @Test
    void mapper_xml_if_tag_body_must_be_multiline() throws IOException {
        List<String> violations = scan(MAIN_MAPPER_XML, "*.xml", SINGLE_LINE_IF);
        assertThat(violations).as("mapper XML 禁单行 <if test=\"...\">content</if>：标签体必须换行（git diff/评审可见性）")
                .isEmpty();
    }

    @Test
    void controller_must_not_use_map_type() throws IOException {
        List<String> violations = scan(MAIN_JAVA.resolve("com/cloudai/system/controller"), "*.java",
                Pattern.compile("Map\\s*<"));
        assertThat(violations).as("Controller 入参/返回禁止 Map——一律 DTO（字段可校验、可演进、可文档化）").isEmpty();
    }

    @Test
    void method_body_max_100_lines() throws IOException {
        // 简单可靠版：签名行（行首可见性修饰符 + "("）起做大括号深度配对，跨行到闭合算方法体行数。
        // 局限：大括号按字符计，不剔除字符串字面量/注释中的大括号（本模块现状无此写法）；接口/抽象方法（";" 先于 "{"）跳过。
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
                    violations.add("cloud_system.sql:" + lineNo + " " + table + " → " + line.trim());
                }
            }
        }
        assertThat(violations).as("DDL 每列必须有 COMMENT（含关联表与审计列）——自查 information_schema 与建表脚本一致")
                .isEmpty();
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
        assertThat(violations).as("同文件 >=2 个 @Value：同前缀多值须封装 @ConfigurationProperties 对象（如 JwtProperties）")
                .isEmpty();
    }

    // ---- 规范 v3：方法命名动词集 / Controller VO 隔离 / 禁三方拷贝 ----

    /**
     * 方法声明行：public 可选（接口方法隐式 public），捕获组=方法名；类型部分必须以字字符开头
     * （防止缩进被当作"类型"，把 validateParent(...) 这类调用行误判成声明）；private/protected 行前置过滤。
     */
    private static final Pattern DECLARED_METHOD =
            Pattern.compile("^\\s*(?:public\\s+)?(?:\\w[\\w<>,\\s\\[\\]]*?)\\s+(\\w+)\\s*\\(");

    /**
     * 方法命名白名单前缀：CRUD 动词集 find/save/update/pageList/list/delete/count；
     * 另放行 reset/assign（重置密码/分配角色菜单——领域动作动词，非通用读写，保留原名）、
     * cancel（请假/审批撤销，2026-10-08 审批平台化）与 getter/setter（get/set/is）。
     * 数据权限轮（2026-10-10）追加：领域动词 evaluate/explain（求值/模拟解释）、logDeny（deny 留痕）、
     * myScope（自查摘要）——求值器与配置服务核心入口；值对象惯用语 all/of（DataScope 工厂）、
     * allows（范围判定）、mask（脱敏应用）、assert（注册表断言 assertResource/assertColumn）——
     * service/dataperm 下值对象非 CRUD 语境，语义化命名优先。
     * 2026-10-10 注册表抽象轮增量：DataPermColumnApplier.apply（反射列应用 D16）/
     * DataPermRegistryConsistencyChecker.collectViolations（一致性检查收集 D17）。
     */
    private static final Pattern NAMING_PREFIX_OK =
            Pattern.compile("^(find|save|update|pageList|list|delete|count|reset|assign|cancel|get|set|is"
                    + "|evaluate|explain|logDeny|myScope|all|of|allows|mask|assert"
                    + "|apply|collect)\\w*");

    /** 语句起始关键字（throw new Xxx( / return foo( 会被误判为声明，前置排除） */
    private static final List<String> STATEMENT_KEYWORDS = List.of(
            "throw", "return", "new", "if", "else", "for", "while", "switch", "catch", "do", "try");

    /** Controller 实体泛型返回：R<SysUser> / R<PageResult<SysRole>> / R<List<SysLeave>>（Vo 后缀不匹配） */
    private static final Pattern R_OF_ENTITY =
            Pattern.compile("R<\\s*((?:PageResult|List)\\s*<\\s*)?Sys(?:User|Role|Menu|Leave)\\s*>");

    /** 三方 Bean 拷贝工具 import（实体→VO 转换一律 convert 包原生 setter） */
    private static final Pattern THIRD_PARTY_BEAN_COPY =
            Pattern.compile("import\\s+[^;]*(?i:beanutils|mapstruct|modelmapper|dozer)");

    @Test
    void method_naming_prefix_whitelist() throws IOException {
        // service+mapper 目录 public 方法名须命中动词集前缀；private 不扫；@Override（toString 等）豁免
        List<String> violations = new ArrayList<>();
        for (Path dir : List.of(MAIN_JAVA.resolve("com/cloudai/system/service"),
                MAIN_JAVA.resolve("com/cloudai/system/mapper"))) {
            for (Path file : listFiles(dir, "*.java")) {
                // CRLF 工作副本：剥行尾 \r，否则 private 豁免的整行 matches() 因 .* 不吃 \r 而失效
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
        assertThat(violations).as("Service/Mapper 方法命名须为 find/save/update/pageList/list/delete/count 前缀")
                .isEmpty();
    }

    @Test
    void controller_returns_vo_only() throws IOException {
        List<String> violations = scan(MAIN_JAVA.resolve("com/cloudai/system/controller"), "*.java",
                R_OF_ENTITY);
        assertThat(violations).as("Controller 禁 DB 实体直出（R<SysUser> 等），一律 XxxVo；"
                        + "跨服务契约 LoginUserDTO 与树节点 MenuTreeNode 不受此限")
                .isEmpty();
    }

    @Test
    void no_third_party_bean_copy() throws IOException {
        List<String> violations = scan(MAIN_JAVA, "*.java", THIRD_PARTY_BEAN_COPY);
        assertThat(violations).as("禁 BeanUtils/mapstruct/ModelMapper/dozer 拷贝——convert 包原生 setter 逐字段").isEmpty();
    }

    // ---- 规范 v4（2026-10-06）：实体内嵌枚举 Enum 后缀 ----

    /** 实体内嵌枚举声明：enum 关键字 + 枚举名 */
    private static final Pattern NESTED_ENUM_DECL = Pattern.compile("\\benum\\s+(\\w+)");

    @Test
    void entity_nested_enum_must_have_enum_suffix() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : listFiles(MAIN_JAVA.resolve("com/cloudai/system/entity"), "*.java")) {
            Matcher m = NESTED_ENUM_DECL.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                String name = m.group(1);
                if (!name.endsWith("Enum")) {
                    violations.add(file.getFileName() + " → enum " + name);
                }
            }
        }
        assertThat(violations).as("实体内嵌枚举必须以 Enum 为后缀（如 StatusEnum/DeletedEnum）").isEmpty();
    }

    // ---- 规则 v5（2026-10-09）：服务间 Feign 声明归位与降级强制 ----

    /** Feign 声明归位：服务模块内不得出现 @FeignClient（合法地 = cloud-*-api 模块；common 程序式特例豁免） */
    @Test
    void feignClients_mustNotBeDeclaredInServiceModules() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String svc : List.of("../cloud-sso", "../cloud-system", "../cloud-bpmn")) {
            Path root = Paths.get(svc, "src", "main", "java");
            for (Path file : listFiles(root, "*.java")) {
                if (Files.readString(file, StandardCharsets.UTF_8).contains("@FeignClient")) {
                    violations.add(svc + "/" + root.relativize(file));
                }
            }
        }
        assertThat(violations)
                .as("服务模块不得声明 @FeignClient——统一放提供方 cloud-<svc>-api 模块（CLAUDE.md 服务间 Feign 规范）")
                .isEmpty();
    }

    /** api 模块内 @FeignClient 必须声明 fallbackFactory（等价降级语义的前提） */
    @Test
    void feignClients_inApiModules_mustDeclareFallbackFactory() throws IOException {
        Pattern annotation = Pattern.compile("@FeignClient\\([^)]*\\)", Pattern.DOTALL);
        List<String> violations = new ArrayList<>();
        for (String api : List.of("../cloud-api/cloud-bpmn-api", "../cloud-api/cloud-system-api")) {
            Path root = Paths.get(api, "src", "main", "java");
            for (Path file : listFiles(root, "*.java")) {
                Matcher m = annotation.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    if (!m.group().contains("fallbackFactory")) {
                        violations.add(api + "/" + root.relativize(file));
                    }
                }
            }
        }
        assertThat(violations).as("api 模块 @FeignClient 必须声明 fallbackFactory").isEmpty();
    }

    // ---- 规则 v6（2026-10-09 RocketMQ 事务消息化）：MQ 消费幂等双层强制 ----

    /**
     * 服务模块 @RocketMQMessageListener 消费者必须走幂等双层之一（注解与类声明同文件，内容判定可靠）：
     * L1 继承 DedupRocketMQListener（通用去重表）或 L2 标注 @UkIdempotentListener（业务 uk 豁免）。
     */
    @Test
    void rocketmqConsumers_mustUseIdempotentListener() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String svc : List.of("../cloud-sso", "../cloud-system", "../cloud-bpmn")) {
            Path root = Paths.get(svc, "src", "main", "java");
            for (Path file : listFiles(root, "*.java")) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.contains("@RocketMQMessageListener")
                        && !source.contains("extends DedupRocketMQListener")
                        && !source.contains("@UkIdempotentListener")) {
                    violations.add(svc + "/" + root.relativize(file));
                }
            }
        }
        assertThat(violations)
                .as("MQ 消费者必须走幂等双层：继承 DedupRocketMQListener（L1 去重表）或标注 @UkIdempotentListener（L2 业务 uk）")
                .isEmpty();
    }

    // ---- 规则 v7（2026-10-09 投影轮）：审批事件唯一监听对象 + 投影表写权归框架 ----

    /**
     * 业务服务模块禁止自建审批事件消费者（契约 2026-10-09-approval-projection-api §2.2 / 设计 D9）：
     * 审批事件（topic APPROVAL_EVENT_NOTIFY）唯一监听对象 = cloud-bpmn-api 投影组件
     * ApprovalProjectionListener（自动装配，消费方显式开）——业务模块零业务 Consumer。
     * 扫描面仅本模块（cloud-system）main 源码：TOPIC_APPROVAL_EVENT_NOTIFY 常量引用或
     * "APPROVAL_EVENT_NOTIFY" 字面量（ApprovalMqTopics import 本身与 TOPIC_TX_APPROVAL_CREATE
     * 生产 topic 不在扫描面——LeaveWorkflowService 事务半消息生产合法引用）。
     */
    @Test
    void service_module_must_not_consume_approval_event() throws IOException {
        List<String> violations = scan(MAIN_JAVA, "*.java",
                Pattern.compile("TOPIC_APPROVAL_EVENT_NOTIFY|\"APPROVAL_EVENT_NOTIFY\""));
        assertThat(violations)
                .as("业务模块禁止自建 APPROVAL_EVENT_NOTIFY 消费者——唯一监听对象=cloud-bpmn-api 投影组件"
                        + "（引 jar + cloud.bpmn.projection.enabled=true 即得，业务零代码）")
                .isEmpty();
    }

    /**
     * 投影表对业务 SQL 只读（设计 D6/D9）：业务 mapper XML 的 insert/update/delete 语句块
     * 不得触碰 approval_projection——写权归框架组件（ApprovalProjectionDao），
     * 业务读走 LEFT JOIN（select 不受限）。块解析沿 master_table_sql_must_handle_deleted 先例。
     */
    @Test
    void projection_table_readonly_for_business_sql() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path xml : listFiles(MAIN_MAPPER_XML, "*.xml")) {
            String content = Files.readString(xml, StandardCharsets.UTF_8);
            Matcher block = Pattern.compile("<(insert|update|delete)\\b[^>]*id=\"([^\"]+)\"(.*?)</\\1>",
                            Pattern.DOTALL)
                    .matcher(content);
            while (block.find()) {
                if (block.group(3).contains("approval_projection")) {
                    violations.add(xml.getFileName() + "#" + block.group(2) + " (write to projection table)");
                }
            }
        }
        assertThat(violations)
                .as("业务 SQL 禁止写 approval_projection（写权归 cloud-bpmn-api 框架组件，业务侧只读 JOIN）")
                .isEmpty();
    }

    // ---- 规则 v8（2026-10-10 对账迁移轮）：xxl-job 装配与任务类落位 ----

    /** 服务模块禁自建 executor：XxlJobSpringExecutor 只能来自 cloud-common-xxljob-starter 装配（xxl-job 整合设计 D1） */
    private static final Pattern NEW_XXL_EXECUTOR = Pattern.compile("new\\s+XxlJobSpringExecutor");

    @Test
    void service_module_must_not_new_xxl_executor() throws IOException {
        List<String> violations = scan(MAIN_JAVA, "*.java", NEW_XXL_EXECUTOR);
        assertThat(violations).as("服务模块禁止 new XxlJobSpringExecutor——executor 装配归 cloud-common-xxljob-starter").isEmpty();
    }

    @Test
    void xxljob_handler_class_must_live_in_job_package() throws IOException {
        // 服务模块本地 @XxlJob 任务类落位约定 job/ 包；api jar 框架组件（projection 对账薄壳）不在服务模块源码，天然豁免
        List<String> violations = new ArrayList<>();
        for (Path java : listFiles(MAIN_JAVA, "*.java")) {
            String content = Files.readString(java, StandardCharsets.UTF_8);
            if (content.contains("@XxlJob(") && !java.toString().replace('\\', '/').contains("/job/")) {
                violations.add(java.getFileName() + " (@XxlJob outside job/ package)");
            }
        }
        assertThat(violations).as("@XxlJob 任务类必须落 job/ 包（本地任务模板约定；api jar 框架组件豁免）").isEmpty();
    }

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

    /** 签名行上方紧邻注解是否为 @Override（toString/equals 等 Object 方法豁免） */
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

    // ---- 扫描基础设施 ----

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
