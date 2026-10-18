# -*- coding: utf-8 -*-
"""一次性落库脚本：在 cloud_ai 库建 ai_kb_grant 表（2026-10-18 知识库权限轮）。

幂等口径：表已存在则跳过（打印 SHOW CREATE TABLE 供验收比对）；建表后回查两索引在案。
连接参数复用工程 DatabaseSettings 默认值（本机 root 空密码），charset utf8mb4 沿中文入库规则。
"""
import pymysql

from cloud_base_ai.config import get_settings

DDL = """
CREATE TABLE ai_kb_grant
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '授权ID（主键）',
    kb_id        BIGINT       NOT NULL COMMENT '知识库ID（ai_knowledge_base.id，弱引用不建外键）',
    subject_type TINYINT      NOT NULL COMMENT '主体类型：0=角色 1=部门 2=用户',
    subject_id   BIGINT       NOT NULL COMMENT '主体ID（sys_role.id / sys_dept.id / sys_user.id，主数据在 cloud_system 库）',
    create_by    VARCHAR(30) NULL COMMENT '授权操作人账号',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间（DB 时钟 SQL NOW() 口径，应用层不传时间）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_subject (kb_id, subject_type, subject_id),
    KEY idx_subject (subject_type, subject_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='AI 知识库授权（纯关系表：物理删除，随保存全删全插、随知识库删除级联清理）'
"""


def main() -> None:
    db = get_settings().db
    conn = pymysql.connect(host=db.host, port=db.port, user=db.user,
                           password=db.password, database=db.database, charset="utf8mb4")
    try:
        with conn.cursor() as cur:
            cur.execute("SHOW TABLES LIKE 'ai_kb_grant'")
            exists = cur.fetchone()
            if exists:
                print("table ai_kb_grant already exists, skip create")
            else:
                cur.execute(DDL)
                conn.commit()
                print("table ai_kb_grant created")
            cur.execute("SHOW CREATE TABLE ai_kb_grant")
            print(cur.fetchone()[1])
    finally:
        conn.close()


if __name__ == "__main__":
    main()
