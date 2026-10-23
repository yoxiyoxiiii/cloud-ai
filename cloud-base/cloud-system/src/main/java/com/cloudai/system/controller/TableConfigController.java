package com.cloudai.system.controller;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.dto.TableConfigSaveRequest;
import com.cloudai.system.service.TableConfigService;
import com.cloudai.system.vo.TableConfigVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户表格列配置端点（契约 2026-10-23-table-config-api §2，网关前缀 /system）。
 * 个人视图偏好（列顺序/显隐/列宽），服务端盲存——只校验格式与上限，不理解列含义。
 * 三端点均免 @PreAuthorize：语义=登录用户自理本人偏好（非管理能力，挂权限注解会把
 * 普通用户的基本功能变成管理入口），沿 my-scope 先例口径；userId 一律取登录态
 * （入参 DTO 编译期即无 userId 字段），未登录由网关鉴权链前置 401。
 */
@RestController
@RequestMapping("/table-config")
@RequiredArgsConstructor
public class TableConfigController {

    private final TableConfigService tableConfigService;

    /** 查询本人配置（§2.1）：无行 data=null（=页面默认列序，非错误不建默认行） */
    @GetMapping("/{tableKey}")
    public R<TableConfigVo> getMyConfig(@PathVariable("tableKey") String tableKey) {
        TableConfigVo vo = tableConfigService.findMyConfig(tableKey);
        return R.ok(vo);
    }

    /** 保存本人配置（§2.2）：upsert 整体覆盖幂等；校验链不过 1002「表格标识格式无效/列配置格式无效」 */
    @PutMapping("/{tableKey}")
    public R<Void> saveMyConfig(@PathVariable("tableKey") String tableKey,
            @RequestBody TableConfigSaveRequest req) {
        tableConfigService.saveMyConfig(tableKey, req);
        return R.ok();
    }

    /** 恢复默认（§2.3）：物理删本人配置行；行不存在同样成功（幂等友好） */
    @DeleteMapping("/{tableKey}")
    public R<Void> deleteMyConfig(@PathVariable("tableKey") String tableKey) {
        tableConfigService.deleteMyConfig(tableKey);
        return R.ok();
    }
}
