"""RAG 域错误码与业务常量集中地（原 service.py 顶部块下沉——retrieval.py 记档的「公共位」落地）。

错误码 AI 段 5xxx，CODE_X 与 MSG_X 成对声明、接续上一号分配（msg 文本即契约，改文案须同步契约文档）。
本模块零 import，任意层可引用（分层依赖单向约束的例外：常量无依赖）。
"""

CODE_LLM_CALL_FAILED: int = 5001
MSG_LLM_CALL_FAILED: str = "LLM 调用失败，请稍后重试"
CODE_KB_NAME_EXISTS: int = 5003
MSG_KB_NAME_EXISTS: str = "知识库名称已存在"
CODE_KB_NOT_EXISTS: int = 5004
MSG_KB_NOT_EXISTS: str = "知识库不存在"
CODE_FILE_NOT_EXISTS: int = 5005
MSG_FILE_NOT_EXISTS: str = "文件不存在"
CODE_FILE_NO_ORIGINAL: int = 5006
MSG_FILE_NO_ORIGINAL: str = "文件无原件，请删除后重新上传"
CODE_FILE_ORIGINAL_READ_FAILED: int = 5007
MSG_FILE_ORIGINAL_READ_FAILED: str = "文件原件读取失败，请删除后重新上传"
CODE_VECTOR_PURGE_FAILED: int = 5008
MSG_VECTOR_PURGE_FAILED: str = "向量清理失败，请重试"
# 5011 接在 AI 段 5009/5010（MCP 客户端通道）之后
CODE_KB_NO_ACCESS: int = 5011
MSG_KB_NO_ACCESS: str = "无权访问该知识库"
# 5012/5013 接在 5011 之后（会话归属校验）
CODE_SESSION_NOT_EXISTS: int = 5012
MSG_SESSION_NOT_EXISTS: str = "会话不存在"
CODE_SESSION_NO_ACCESS: int = 5013
MSG_SESSION_NO_ACCESS: str = "无权访问该会话"
# 5014 接在 5013 之后（上传链向量写失败）；AI 段下一可用 5015
CODE_VECTOR_WRITE_FAILED: int = 5014
MSG_VECTOR_WRITE_FAILED: str = "向量写入失败，请对该文件执行重新向量化"
# 创建会话时身份缺失（X-User-Id 缺失/非整数）无法落归属——复用通用参数错误码 1001
CODE_SESSION_IDENTITY_MISSING: int = 1001
MSG_SESSION_IDENTITY_MISSING: str = "缺少用户身份（X-User-Id），无法创建会话"
CODE_CHUNK_PARAM_INVALID: int = 1001
MSG_CHUNK_PARAM_INVALID: str = "分片重叠必须小于分片大小"
# 父块分片大小固定 500 不开放（文件表无父块快照列——开放需 DDL，欠账登记 docs/handoff/ai.md）
PARENT_CHUNK_SIZE_FIXED: int = 500
# 首问改题的标题截断长度（沿用前端既有 slice(0,16) 口径，生成责任收敛服务端保证多端一致）
TITLE_MAX_CHARS: int = 16
