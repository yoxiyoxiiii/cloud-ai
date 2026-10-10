package com.cloudai.system.service.dataperm;

import com.cloudai.system.entity.SysDataPermColumn;
import com.cloudai.system.mapper.DataPermColumnMapper;
import com.cloudai.system.mapper.DataPermRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据权限注册表一致性检查器（设计 D17 后半，warn 不阻断）：启动后比对 DB 存量规则 ⊆ 代码注册表——
 * ① sys_data_perm_rule 全表 DISTINCT resource ⊆ 注册表（越界=未注册资源：不应存在——写入经 3034 校验链，
 * 出现即历史脏数据或注册表收缩遗留）；
 * ② sys_data_perm_column 全表 DISTINCT (resource, column_key) ⊆ 对应资源可配列清单（越界=孤儿列规则：
 * VO 字段改名 / 列清单收缩后的存量，规则永不命中且求值时被工具静默跳过——最需人工排查的形态）。
 * 越界逐条 log.warn + 一条汇总，不删数据不阻断启动不留痕（开发期观测件非审计件）。
 * 载体口径（设计 §3.3 记档）：ApplicationRunner 上下文就绪后执行（@PostConstruct bean 初始化期不宜触 DB
 * 被弃；守护测试测不到运行环境 DB 存量只能作补充）；run 整体 try-catch——检查自身失败 log.error
 * 不影响启动。索引零新增（设计 §8：启动一次性全扫配置态小表，无高频 WHERE 路径）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataPermRegistryConsistencyChecker implements ApplicationRunner {

    private final DataPermRuleMapper ruleMapper;
    private final DataPermColumnMapper columnMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<String> violations = collectViolations();
            if (violations.isEmpty()) {
                return;
            }
            violations.forEach(violation ->
                    log.warn("数据权限注册表一致性越界：{}（疑似孤儿规则，请人工核对处置）", violation));
            log.warn("数据权限注册表一致性检查完毕：共 {} 条越界（检查不阻断启动，请人工核对处置）",
                    violations.size());
        } catch (Exception e) {
            log.error("数据权限注册表一致性检查执行失败（不影响启动）", e);
        }
    }

    /** 包可见可测：DB 存量 ⊄ 注册表的越界描述清单（rule 路先 column 路后，顺序稳定；不依赖日志捕获） */
    List<String> collectViolations() {
        List<String> violations = new ArrayList<>();
        for (String resource : ruleMapper.listDistinctResources()) {
            if (!DataPermResources.isRegistered(resource)) {
                violations.add("资源 " + resource + " 未注册");
            }
        }
        for (SysDataPermColumn column : columnMapper.listDistinctResourceColumns()) {
            if (!DataPermResources.getConfigurableColumns(column.getResource())
                    .contains(column.getColumnKey())) {
                violations.add("资源 " + column.getResource() + " 列 " + column.getColumnKey()
                        + " 不在可配清单（疑似孤儿规则）");
            }
        }
        return violations;
    }
}
