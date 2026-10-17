package com.cloudai.system.mcp;

import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.translate.domain.DictItemEntry;
import com.cloudai.system.service.SysDictDataManageService;
import com.cloudai.system.service.SysUserManageService;
import com.cloudai.system.vo.SysUserVo;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * cloud-system MCP 工具面（契约 2026-10-17-mcp-tools-api §2-§4：两个只读工具）。
 * 委托既有 ManageService，零新 SQL；工具名/描述/权限档位与契约逐字一致。
 */
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
    public PageResult<SysUserVo> queryUsers(
            @ToolParam(description = "页码，从 1 开始") Integer pageNum,
            @ToolParam(description = "每页条数，上限 200（对齐分页插件 maxLimit）") Integer pageSize) {
        McpPermGuard.require("system:user:list");
        PageQuery query = new PageQuery();
        if (pageNum != null) {
            query.setPageNum(pageNum);
        }
        if (pageSize != null) {
            query.setPageSize(pageSize);
        }
        return userManageService.pageList(query);
    }

    /** 按字典键查询启用字典项（只读）；登录即可调用，键不存在返回空数组（契约 §4.3 容错语义） */
    @Tool(name = "system_query_dict_items",
            resultConverter = McpToolResultConverter.class,
            description = "按字典键查询启用中的字典项（只读）。返回 value（存库值）/label（展示标签）/sort（排序）。登录即可调用，无需特定权限。")
    public List<DictItemEntry> queryDictItems(
            @ToolParam(description = "字典类型键，如 user_status") String dictKey) {
        return dictDataManageService.listByDictKey(dictKey);
    }
}
