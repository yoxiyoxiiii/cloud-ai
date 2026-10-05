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
        // 词边界匹配：sys_role_menu 不算 sys_role/sys_menu（关系表无逻辑删除）
        Pattern masterTable = Pattern.compile("\\bsys_user\\b|\\bsys_role\\b|\\bsys_menu\\b");
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
        assertThat(violations).as("master table (sys_user/sys_role/sys_menu) SQL must have explicit deleted").isEmpty();
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
