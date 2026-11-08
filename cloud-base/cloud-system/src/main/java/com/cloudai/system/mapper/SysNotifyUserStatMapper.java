package com.cloudai.system.mapper;

import com.cloudai.system.entity.SysNotifyUserStat;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 站内信未读统计表 SQL（XML：mapper/SysNotifyUserStatMapper.xml）。
 * 派生计数表：真相源是收件表，本表由发送/已读写路径同事务维护；
 * 统计行缺失或漂移时读路径兜底零值、每日对账全量重建收敛（无业务删除入口）。
 * 语句集只做单表单键简单读写：分组、建行、对账编排全在 Service 层。
 */
public interface SysNotifyUserStatMapper {

    /** 单行 PK 查询：summary 30s 轮询取数（无行=该用户无收件，Service 兜底 0/0） */
    SysNotifyUserStat findByUser(@Param("userId") Long userId);

    /** 单行 INSERT：发送路径该用户首条收件建行（并发首建撞主键由 Service 捕获吸收后重试递增） */
    int save(SysNotifyUserStat stat);

    /** 普通多行 INSERT：对账重灌专用（前一步已 deleteAll 清空，无主键冲突） */
    int saveBatch(@Param("list") List<SysNotifyUserStat> list);

    /** 发送路径：单用户原子递增 + 水位直接覆盖（收件行 id 单调递增，Service 传批内该用户最大 id） */
    int updateIncrease(@Param("userId") Long userId,
                       @Param("delta") int delta,
                       @Param("lastReceiptId") long lastReceiptId);

    /** 已读路径递减：按实际影响行数守卫递减（防负数；统计行缺失时 0 行，留给每日对账收敛） */
    int updateDecrease(@Param("userId") Long userId, @Param("delta") int delta);

    /** 对账第一步：清空统计表（随后 Service 组装 saveBatch 重灌，同一事务） */
    int deleteAll();
}
