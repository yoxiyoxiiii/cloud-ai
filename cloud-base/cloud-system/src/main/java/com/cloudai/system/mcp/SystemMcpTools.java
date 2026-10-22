package com.cloudai.system.mcp;

import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.mcp.perm.McpRequirePerm;
import com.cloudai.common.mcp.tool.McpToolResultConverter;
import com.cloudai.common.translate.domain.DictItemEntry;
import com.cloudai.system.api.dataperm.DataPermColumnApplier;
import com.cloudai.system.api.dataperm.DataPermColumnContext;
import com.cloudai.system.service.SysDictDataManageService;
import com.cloudai.system.service.SysUserManageService;
import com.cloudai.system.vo.SysUserVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * cloud-system MCP 工具面（契约 2026-10-17-mcp-tools-api §2-§4：两个只读工具）。
 * 委托既有 ManageService，零新 SQL；每个工具方法的权限档位以方法上的注解声明，
 * 由 starter 切面统一强制执行（javadoc 不裸写注解名字面串——守护测试按文本计数会误判）。
 * 出参含数据权限白名单 VO 的工具必须在返回前应用列动作（MCP 端点走 RouterFunction，
 * REST 面的列 advice 不经过——列改写是逐工具义务，守护测试构建期强制）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemMcpTools {

    private final SysUserManageService userManageService;
    private final SysDictDataManageService dictDataManageService;

    /**
     * 分页查询系统用户（只读）。入参缺省走 PageQuery 默认（1/10）；
     * pageSize 上限 200 由分页插件 maxLimit 钳制（契约 §3.2）。
     */
    @Tool(name = "system_query_users",
            resultConverter = McpToolResultConverter.class,
            description = "分页查询系统用户列表（只读）。返回账号、昵称、部门、状态等；不含密码。需要权限 system:user:list。")
    @McpRequirePerm("system:user:list")
    public PageResult<SysUserVo> queryUsers(
            @ToolParam(description = "页码，从 1 开始") Integer pageNum,
            @ToolParam(description = "每页条数，上限 200（对齐分页插件 maxLimit）") Integer pageSize) {
        PageQuery query = new PageQuery();
        if (pageNum != null) {
            query.setPageNum(pageNum);
        }
        if (pageSize != null) {
            query.setPageSize(pageSize);
        }
        PageResult<SysUserVo> page = userManageService.pageList(query);
        applyColumnActions(page);
        return page;
    }

    /**
     * MCP 出参列动作应用：pageList 已把列决策写入 ThreadLocal 上下文（SYNC 工具形态下
     * 与工具体同线程），此处逐行改写——REST 面由列 advice 在响应边界统一应用，MCP 端点
     * 不经过该边界，漏应用即明文泄露。上下文缺失按 fail-closed 拒绝返回，不原样出参。
     */
    private void applyColumnActions(PageResult<SysUserVo> page) {
        DataPermColumnContext.ColumnDecision decision = DataPermColumnContext.get();
        if (decision == null) {
            // 3042 与文案同源于列 advice 的冻结口径（system 段既有错误码，跨包常量不可见故字面量）
            log.error("MCP 工具出参列处理上下文缺失，已拒绝返回：system_query_users");
            throw new BusinessException(3042, "数据权限列处理异常，响应已拒绝");
        }
        page.getRows().forEach(vo -> DataPermColumnApplier.apply(vo, decision.columnScope()));
    }

    /** 按字典键查询启用字典项（只读）；登录即可调用（空值注解显式声明档位），键不存在返回空数组（契约 §4.3 容错语义） */
    @Tool(name = "system_query_dict_items",
            resultConverter = McpToolResultConverter.class,
            description = "按字典键查询启用中的字典项（只读）。返回 value（存库值）/label（展示标签）/sort（排序）。登录即可调用，无需特定权限。")
    @McpRequirePerm
    public List<DictItemEntry> queryDictItems(
            @ToolParam(description = "字典类型键，如 user_status") String dictKey) {
        return dictDataManageService.listByDictKey(dictKey);
    }
}
