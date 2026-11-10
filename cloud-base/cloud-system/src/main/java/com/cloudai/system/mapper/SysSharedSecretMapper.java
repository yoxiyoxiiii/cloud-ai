package com.cloudai.system.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.system.dto.InnerSecretPageQuery;
import com.cloudai.system.entity.SysSharedSecret;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 共享密钥生命周期表 SQL（XML：mapper/SysSharedSecretMapper.xml）。
 * 追加式流水无 deleted 列（沿 mq_tx_log 先例）：无逻辑删除语义，状态由 updateStatus 单调推进；
 * 审计时间列由 SQL NOW() 取数据库时钟，操作人由 Service 显式传参。
 */
public interface SysSharedSecretMapper {

    SysSharedSecret findById(@Param("id") Long id);

    /** 最新生效行（scene 等值 + seq 取最大，uk_scene_seq 最左前缀；status∈{1,2}——已过期/作废行不参与轮换判定） */
    SysSharedSecret findLatestActive(@Param("scene") String scene);

    /** 场景内最大代数（活跃集 version 来源；无行返回 null） */
    Long findMaxSeq(@Param("scene") String scene);

    /** 分页查询：无 LIMIT，由 PaginationInnerInterceptor 追加；status 精确过滤可选 */
    IPage<SysSharedSecret> pageList(Page<SysSharedSecret> page, @Param("query") InnerSecretPageQuery query);

    /** 按状态列表查询（任务清扫与活跃集计算的读集；表极小不分页） */
    List<SysSharedSecret> listBySceneStatus(@Param("scene") String scene,
                                            @Param("statuses") List<Integer> statuses);

    int save(SysSharedSecret secret);

    /** 状态推进（1→2 带 retireTime / →3 不改 retire / →4 带 retireTime=now；update_time 取 NOW()） */
    int updateStatus(@Param("id") Long id,
                     @Param("status") Integer status,
                     @Param("retireTime") LocalDateTime retireTime,
                     @Param("updateBy") String updateBy);
}
