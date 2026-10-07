# 翻译铺开 + 内置数据 UI 强化 + e2e 两债清偿 设计（决策记录）

- 日期：2026-10-07
- 需求：清小债 +「候选 3 四页翻译铺开 ∥ builtin 徽标/按钮禁用」合成轻量全栈一轮（用户拍板：Q1-Q8 全按架构倾向落定）
- 契约：`2026-10-07-translation-api.md` §8（v1.1 铺开节）+ `2026-10-07-builtin-protection-api.md` §7（v1.1 UI 强化节）——本文档不重复契约字段表
- 计划：`docs/superpowers/plans/2026-10-07-translate-rollout-builtin-ui.md`
- 轨道：**全栈 A 级**——后端章 ∥ 前端章并行 + e2e 章殿后；cloud-system 重启一次（卡点），前端 5173 agent 自管
- 定位：**决策记录为主**。翻译机制（注解/Advisor/缓存/降级语义/TTL）与保护机制（is_builtin 判定/12 处校验/错误码）均已落地，本文档只记本轮增量决策，不重述机制——机制语义见 translation-api §1/§5 与 builtin-protection §1/§2

## 0. 总原则（红线，源自拍板）

- **保护矩阵/错误码（3013-3017 集合）/权限标识/事务/索引零变化**；MapperXmlBindingTest 语句计数 46 不动（零 mapper 语句变更）
- **既有端点 additive-only**：只加 VO 出参字段，入参/既有字段/成功语义零触碰；`user_status` 与 admin 种子**零触碰**（common_status 是新增不是改名）
- UI 禁用面 = builtin-protection §2 矩阵的**逐行镜像**，不自行加码不减配（Q4/Q6 拍板）

## 1. 决策 D1：status 翻译字典键 = 新增内置种子 common_status

- role/menu/dictType/dictData 四域 status（0 正常/1 停用）经 `@DictTrans(dictKey="common_status")` 翻译；**SysUserVo 保持 user_status 零触碰**（translation-api §3 语义不变）
- 种子：sys_dict_type（通用状态/common_status/is_builtin=1）+ 2 项（正常"0"/停用"1"，is_builtin=1），遵循种子惯例（create_by='system'，基线+增量双落）
- **落库关键差异（与 user_status 先例）**：user_status 落地时字典两表为空库、显式 id=1/1,2 干净；本轮存量库的 AUTO_INCREMENT 已被多轮 e2e 消耗——**增量脚本不写显式 id**（防主键冲突），项的 type_id 经 `INSERT...SELECT` 按 dict_key 关联；基线 cloud_system.sql 为全新建库，显式 id=2（类型）/3,4（项）
- **id 非契约内容**：内置保护判定本体是 is_builtin 列（§1 既有语义），common_status 在存量/新建两环境的 id 不同均合法——契约与保护矩阵不把其 id 写成规范；builtin-protection §7 以「内置范围扩张声明」覆盖（自动受 3015/3016）
- 排除项：复用 user_status（语义永久错位且禁改名）、每域一种子（纯冗余）——分析阶段已议，不赘

## 2. 决策 D2：builtin 字段五域全出，Boolean 型，null-safe 传递

- 五 VO additive `private Boolean builtin;`（Lombok getter→Jackson 出 `builtin`，对齐保护契约 §6 预告的 `builtin: boolean`；不用 isBuiltin 字段名——Boolean 包装型 getIsBuiltin 会序列化成 "isBuiltin"）
- 传递点四个 Convert（SysRole/SysUser/SysDictType/SysDictData）各 +1 行 setter + MenuTreeBuilder.toNode +1 行；`Integer.valueOf(1).equals(entity.getIsBuiltin())`（isBuiltin null → false，新建行恒 false）
- 生效端点（契约 §7 逐个声明）：`/system/role/page`、`/system/role/list`（分配角色弹窗候选——流入不消费，additive 无害）、`/system/menu/tree`（菜单页 + AssignMenuDialog 共用，同上）、`/system/dict/type/page`、`/system/dict/data/page`、`/system/user/page`、`/system/user/{id}`
- **写出口维持零**：builtin 只读出参；请求侧不可传（SaveRequest 无此字段、实体写列枚举不含 is_builtin——结构性防篡改不因出参放开而破坏）
- 索引零增量：builtin/statusLabel 均不进任何查询条件，无新查询路径（allColumns 上轮已含 is_builtin）

## 3. 决策 D3：UI 形态 = 徽标 + 写按钮禁用（矩阵镜像），用户域例外按 §2 放行项表达

- **徽标**：`el-tag type="info" size="small"` 文本「内置」，内联于名称类单元格（role.name / menu.name / dictType.dictName / dictData.label / user.account 五处统一形态）；**不新增列**（列结构稳定，e2e 列序断言少动）；不抽公共组件（cloud-web 尚无 src/components 共享组件惯例，5 处内联 2 行可接受，一致性由 e2e 徽标断言锚定）
- **禁用**（`:disabled="row.builtin"`，逐域清单见契约 §7 表）：role 行 编辑/分配权限/删除；menu 行 编辑/删除；dictType 行 编辑/删除（**「字典项」按钮保持可用**——D6）；dictData 行（弹框内）编辑/删除（**新增保持可用**——D6）；user 行 删除/分配角色（**编辑、重置密码保持可用**）
- **用户域例外表达**（对齐 §2 用户行）：编辑弹窗照常打开（昵称可改），仅「停用」radio `:disabled`（正常项不禁，选择无危害）；不把弹窗整体锁死（后端放行 = 合法运维，UI 加码即与后端语义分叉）
- 隐藏按钮方案否决（失去可发现性/可断言性）；保留可点靠 toast 方案否决（§5.4 预告即禁用）
- AssignMenuDialog / AssignRoleDialog 内部零改（builtin/译文字段流入，TS additive 即编译兼容）

## 4. 决策 D4：四脚本保护断言迁移 = UI 禁用断言 + page.request 直连双层（语义更强）

- 每域双层：**前端防线**（徽标可见 + 写按钮 `isDisabled()`）∥ **后端防线**（`page.request` 带 localStorage token 直连写端点 → body 3013-3017；N5 403 直连先例——不入 page 网络统计、不污染 *-VERIFY；请求体中文经 Node UTF-8 无 Git Bash GBK 陷阱）
- 保留的既有语义断言点（全部转 API 层，不损失覆盖）：
  - **3015 先于 3011**（D5b 次序断言）：直连 DELETE /system/dict/type/1 → 3015 而非 3011
  - **3013「原值亦拒」**（R6b 编辑全禁语义）：直连 PUT /system/role 提交与库中原值相同的全量字段 → 仍 3013
  - S14b 编辑路径新形态：弹窗可开 + 「停用」radio disabled（UI）+ 直连 PUT /system/user {id:'1', status:1, nickname:原值} → 3017
- **expectErrToast 助手退役**：标准 UI 下 3013-3017 不再可触发（按钮禁用在前），四个脚本的保护场景 toast 断言删除；拦截器 toast 路径由 3009/3011/3012 等既有断言持续覆盖，不加替代
- 种子终态断言（行仍在/昵称状态原样/CLEANUP 计数）全部照旧保留
- **R5a 迁移**（admin「分配权限」按钮被禁的唯一非保护断言碰撞）：
  - admin 存量绑定语义（绑定含父目录 id）改直连 `GET /system/role/1/menus` → 断言 23 个种子菜单 id 全量（含 10/20 等父 id）——语义本体保留且更强（原 UI 断言只看全选态）
  - 弹窗分组结构断言（perm-group/perm-menu-row/perm-func/perms 灰字）不依赖 admin 行——移至 R5b 测试角色首次开窗处执行
- **徽标入名称格的连带迁移**：内联徽标使名称单元格 innerText 变为「{name}内置」——`findMenuRow`（cells[0] === name 精确比对）与 D5b 的 cellsAfter[0] === '用户状态' 等名称列断言，统一先剥徽标再比对（`stripBadge = (s) => s.replace(/内置$/, '')`）；role/dict 找行锚在 cells[1]（roleKey/dictKey）、user 用 hasText（account）均不受影响

## 5. 决策 D5：翻译铺开范围与降级链

- **后端注解四 VO 统一三项**（@TranslateVO + status→statusLabel(common_status) + createBy/updateBy→*Name），与 SysUserVo 样板同构；dict 两 VO 的审计译文**后端照给、前端暂不消费**（Round E「类型表/项弹框不展示审计列」是记档取舍，不翻案）——注解统一换一致性，UI 消费按页可选
- **前端消费**：role/menu 页三列（状态 tag 文本/创建人/更新人）走用户页同款降级链；dict 类型表 + DictDataDialog 仅状态列（statusLabel 降级链）；STATUS_MAP 本地映射保留为降级文案与 tagType 依据（颜色永远按原 status，契约 §1 红线）
- **降级链全页强制**（契约 §3.1 同款）：`statusLabel ?? STATUS_MAP[status].label ?? status`；`createByName ?? createBy ?? '-'`
- 种子行 createBy='system'（非真实账号）→ createByName 恒 null → 前端降级显示 'system'，属正确降级非故障（与 admin 审计列 null 同口径，e2e 勿误读）
- 译文 null 多因一果/30 分钟陈旧窗口/消费口径等宽松语义**全部沿用 translation-api §1/§5**，本轮零新增宽松语义（common_status 项被误删的后果同为译文 null + 消费端点空数组，已被既有条目覆盖）
- 机制交叉验证点：MenuTreeNode 位于 dto 包（非 vo 包），翻译 Advisor 按注解扫描应与包名无关——**实现时验证**（`/menu/tree` 响应级回填）；若 Advisor 实现有包过滤则调整，回报所选通路

## 6. 决策 D6：内置字典类型的「新增项」随矩阵放行（Q6 拍板）

- 后端保护面：类型 PUT/DELETE（3015）+ 既有项 PUT/DELETE（3016）；**向内置类型新增项（POST）不在保护面 = 放行**。UI 严格随矩阵：内置类型行「字典项」按钮可用、弹框内「新增」可用、种子项行编辑/删除禁用
- 记宽松语义（契约 §7）：common_status/user_status 类型可被追加项（管理动作合法）；追加项 is_builtin=0 可正常编辑删除；误删种子项的后果 = 译文降级 null + 消费端点空数组（优雅降级不崩溃）
- 如未来要「内置类型整体冻结」，属保护矩阵收紧（后端 12 处校验扩张），另案明示，不在本轮夹带

## 7. 决策 D7：e2e 两债修法（治本 + 双守卫）

- **副本漂移根除**：`findRowByCell(page, { path, cellIndex, value })` 抽入 lib/harness.mjs（以 run-role 已修版为体：waitForResponse `*/page` → goto → 等响应落定 → 首行/分页可见 → waitTableIdle → 翻页循环按 cell 精确比对）；run-role/run-nav 两脚本删除本地 findRoleRowByKey 改调共享版——nav 竞态由此修复，且此类「修了 A 忘 B」的副本漂移结构性根除。run-dict 的 findTypeRowByKey 自带 respP 等待且作用域在 scoped 面板，**不迁**（避免无谓重构半径）
- **logoutViaUi 双守卫**：
  1. 时序修：每轮点击后以 `waitForURL('**/login', { timeout: 4000 })` 取代盲等 600ms，URL 已落定则循环条件自然退出
  2. 请求级守卫：函数生命周期内挂一次性 logout POST 响应等待，`sawLogout` 置位后**永不再点**（结构上杜绝「重定向未完成时二击 → 第二个 POST 撞已失效会话得 401」）
  3. 残余理论窗口（首击 POST 迟于 4s 未返回且 URL 未变）由下条常驻断言兜底
- **T-VERIFY 常驻断言**：harness state 增 `logoutViaUiStats`（每次调用记录 {clicks, posts}，posts 由 apiCalls 差分计数）；run-scaffold T-VERIFY 断言每次调用 posts ≤ 1——把偶发 flake 的修复转化为永久回归守卫（nav N-VERIFY 顺带同款一行，可选）
- **统计性验证口径**（flake 无法确定性复现）：scaffold + user 两脚本各重复跑 5 次，零 console error（favicon 白名单照旧）/零 ≥400——与常驻断言共同构成「修好」的证明

## 8. 决策 D8：契约修订形态 = 两现行契约 additive 节（Q5 拍板）

- translation-api 追加 **§8 铺开节**（自带 v1.1 变更点清单）：四 VO 译文字段（语义引用 §3.1 不重抄，仅列差异：statusLabel 字典键 = common_status）、生效端点、common_status 种子声明（id 非契约）、TS additive、前端消费映射增行
- builtin-protection 追加 **§7 UI 强化节**（自带 v1.1 变更点清单）：五域 builtin 字段表 + 生效端点、UI 禁用/徽标矩阵（§2 镜像）、**声明性关闭 §5.4**（错误 toast 不再是全部前端反馈，改为徽标+禁用为主、3013-3017 保留为最终防线）与 **§5.5**（is_builtin 经 builtin 字段只读可见；写出口仍零，结构性防篡改维持）、common_status 内置范围扩张声明
- MenuTreeNode 的两类 additive 字段在两节各自声明并交叉引用 menu-management §3（translation-api §3 对 pilot 的「additive 声明 + 原文不回改」先例）；两契约 §0 变更点清单各补指针行，v1 原文条款零回改
- 错码零新增、权限标识零新增（无新端点/无新 @PreAuthorize，禁用路径不产生任何码）

## 9. 决策 D9：轻量约束与不做清单

- spec/契约/计划三文档紧凑；e2e 翻译断言**每页 UI 一条 + 页内 fetch 一条**（原字段与译文/builtin 并存的红线黑盒断言，S12b 先例），不逐列穷举
- 不做：工作台/导航（UserNavVo 无 status/审计/builtin 语义）、菜单 type 列（M/C/F）字典化（本地 TYPE_MAP 与降级链同构已达标）、dict 审计列复辟（Round E 取舍）、AssignRoleDialog 候选徽标（可选打磨记移交）、公共 BuiltinTag 组件（无共享组件惯例，不为 2 行设新约定）
- 单测从简：MenuTreeBuilderTest 补 builtin 传递断言（唯一有既有测试面的传递点）；四个 Convert 无既有测试面，不为 1 行 setter 开新测试面——覆盖靠 B5 curl 验收 + e2e 双层断言；ArchitectureGuardTest 自动受检（VO 规则/javadoc/两行式）

## 10. 验收总口径

- 后端：全量构建绿（守护计数 46 不变）+ 9202 重启后 curl 验收（四端点译文/builtin 形态 + common_status 消费端点 + 保护冒烟抽验）
- 前端：两次 build 绿 + 四页/弹框联调（徽标/禁用/翻译列/降级链）
- e2e：六脚本全量绿（断言迁移后）+ scaffold/user ×5 重复跑零污染 + 种子终态断言全绿
