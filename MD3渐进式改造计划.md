# ClassicWiki · Material Design 3 / M3 Expressive 渐进式改造计划

> 状态：**Stage 1–14 已完成（含真机验证）**
> 基线 APK：`app/build/outputs/apk/debug/app-debug.apk`（2026/10/20 17:41:55，7,545,781 字节）
> 约束：只改 UI 层。不改数据结构、API、存储、业务流程。

## 0. 决策记录（2026-10-20 确认）

| 议题 | 决定 |
|---|---|
| 推进顺序 | 设置模块优先：Stage 1 令牌层 → Stage 2 设置 → 3 歌曲百科 → 4 我的贡献 → 5 上传/下载 → 6 主框架补完 |
| Expressive | 先用令牌模拟（不升级 material 1.11.0），Stage 8 再单独评估升级 |
| 我的贡献顶部三卡 | **结构不动**，只统一间距/圆角/字号/列表密度；通道卡片暂不移位（避免与设置页入口重复且不一致） |
| 截图对比 | 原计划用布局预览截图。**当前环境无设备、未安装 emulator、无法驱动 Android Studio 预览**，Stage 1 改为「值等价映射表」证明零视觉差异；截图能力待设备接入后补齐 |


---

## 1. 现状盘点（基于代码实测，不是估计）

### 1.1 技术栈

| 项 | 现状 |
|---|---|
| 语言 / UI 方案 | Java 8，**纯 View 体系**（无 Compose、无 Fragment） |
| 依赖 | AppCompat 1.6.1、Material **1.11.0**、RecyclerView 1.3.2、core 1.12.0、SwipeRefreshLayout 1.1.0、activity 1.8.0 |
| SDK | minSdk 26 / target & compileSdk 34 |
| 主题 | `Theme.Material3.DayNight.NoActionBar`，自建 32 个色彩令牌 |
| 调色板 | 4 套预设 × 明/暗（`palettes.xml` / `values-night/palettes.xml`），Android 12+ 可选动态取色（`WikiApp` 条件启用） |
| 结构 | 单模块 `:app`，10 个 Activity，4 个「目的地」由 `MainActivity` 用 View 显隐切换 |
| 布局 | 23 个 layout，Material 组件 157 处，原生控件 281 处，硬编码尺寸 125 处 |

### 1.2 页面地图

```
MainActivity（单 Activity 宿主）
├─ 顶栏 MaterialToolbar + 上传进度条
├─ content_host
│   ├─ SoundCloudDownloadView   声云下载（URL → 多曲目 → 传入上传）
│   ├─ AlbumUploadView          专辑上传（长表单 + 艺人搜索弹窗 + 歌曲列表）
│   ├─ WikiQueryView            歌曲百科（输入 → 四态 → 历史/复制/外链）
│   └─ page_contributions       我的贡献（通道卡 + 账号卡 + 搜索卡 + Tab + 状态 Chip + 列表 + 分页器）
└─ bottom_overlay（BackdropBlurView + 分页器 + 悬浮胶囊底栏，4 个目的地）

SettingsActivity（一级索引，7 张填充卡片）
├─ AppearanceSettingsActivity   外观（主题/调色板/字体/动效/触感/导航样式）
├─ DisplaySettingsActivity      显示（封面、卡片显隐、搜索布局）
├─ AccountSettingsActivity      账号
├─ NotificationSettingsActivity 通知（通道状态 + 测试 + 跳转）
├─ AuditSettingsActivity        后台审核提醒（开关 + 轮询 Slider + 保活）
└─ AboutSettingsActivity        关于

LoginActivity / CoverCropActivity
对话框：ArtistPickerDialog、dialog_song_edit、单选子菜单、审核消息列表
```

### 1.3 已经达标的 MD3 基础（不重复造）

- 32 个色彩令牌 + `values-night` 全覆盖，动态取色与预设调色板互斥逻辑正确；
- 4dp 栅格间距令牌（`spacing_xs/sm/md/lg/xl`）；
- `MaterialToolbar` 统一顶栏样式、`MaterialCardView` 填充/海拔两套、`Chip` / `TabLayout` / `MaterialSwitch` / `Slider` / `TextInputLayout` 已在用；
- edge-to-edge 已开启，`BaseActivity` 统一补偿系统栏；`handlesBottomInsets()` 处理悬浮底栏与输入法冲突；
- 无裸 `textSize`，字号全部走 `textAppearance`（这点做得好，不要破坏）；
- 预见性返回、差异化振动、动效强度设置已具备。

### 1.4 真实差距（改造依据）

| # | 问题 | 证据 |
|---|---|---|
| G1 | **令牌层不完整**：只有间距+色彩，缺形状阶、动效令牌、海拔/状态层 | 硬编码 125 处 dp/sp；`MotionManager` 里 `300/220ms` 写死；圆角散落 12/16/20dp |
| G2 | **设置页不是 MD3 列表语义**：7 张大卡片堆叠，每张约 88dp | `activity_settings.xml` 单文件 38 个原生控件 / 31 处硬编码尺寸 |
| G3 | **列表密度未对齐**：贡献卡 56dp 封面 + 16dp 内边距 + 双行徽标，信息层级平铺 | `item_contribution.xml` 无统一 list-item 节奏 |
| G4 | **弹窗形态单一**：9 处 `MaterialAlertDialogBuilder` + 1 处原生 `AlertDialog.Builder`；无任何 BottomSheet / Snackbar | 艺人选择、歌曲编辑、页码选择都用 AlertDialog |
| G5 | **反馈通道靠 Toast**：33 处 Toast，无 Snackbar（无法承载撤销/重试动作） | 上传/下载/登录结果无法在页面内确认 |
| G6 | **大屏缺失**：`content_max_width` 声明了但**没有任何引用**，无 `layout-sw600dp` | 全项目 grep 仅命中 dimens 定义 |
| G7 | **Insets 实现待收敛**：仍用已废弃的 `setStatusBarColor` / `setNavigationBarColor`，底部高度靠实测覆盖层手算 | `BaseActivity.applyEdgeToEdge()`、`MainActivity` 覆盖层测量 |
| G8 | **M3 Expressive 为零**：Material 1.11 不包含 Expressive 组件；无 expressive 字号强调、形状 morph、按钮组、加载指示器变体 | `build.gradle` 依赖版本 |
| G9 | **无障碍未审计**：装饰性图标部分缺 `importantForAccessibility`，触控热区未统一校验 | 散落各布局 |

---

## 2. 目标与非目标

**目标**
1. 建立一套项目内自有的 MD3 令牌层（色彩已有，补齐形状/字号别名/动效/海拔/密度），所有页面只引用令牌；
2. 沉淀 8~10 个可复用 View 组件，替换重复布局；
3. 按页面分批迁移，**每一阶段都能编译、能装、能跑**，业务逻辑零改动；
4. 优先解决：页面层级、列表密度、底部导航、系统栏 Insets、弹窗与表单状态。

**非目标（明确不做）**
- 不引入 Compose、不改 Fragment 架构、不重写 Activity；
- 不动 `NetEaseWikiClient` / `WikiPrefs` / 上传下载流程 / FCM / 轮询业务；
- 不为「看起来像 MD3」而改配色或堆圆角。

---

## 3. 令牌层设计（Stage 1 交付）

新增 `res/values/tokens.xml`（+ `values-night` 覆盖仅必要时）：

```
形状阶     shape_none 0 / shape_xs 4 / shape_sm 8 / shape_md 12
           shape_lg 16 / shape_xl 20 / shape_xxl 28 / shape_full 50%
           语义别名：shape_card / shape_sheet / shape_field / shape_nav / shape_badge
字号别名   type_display / type_headline / type_title / type_body / type_label
           映射到 Material3 textAppearance，禁止页面直接写 ?attr/textAppearanceXxx
动效       motion_short_1 50 / short_2 100 / short_3 150 / short_4 200
           medium_1 250 / medium_2 300 / medium_3 350
           long_1 400 / long_2 500
           插值器：emphasized_decelerate / emphasized_accelerate / standard
海拔       elev_0 / elev_1 / elev_2 / elev_3（映射 cardElevation / shadow）
密度       item_height_one_line 56 / two_line 72 / three_line 88
           touch_target_min 48 / divider_inset 16
```

同时：
- 把 `MotionManager` 的 `300/220ms` 改为读取令牌；
- 建立 `res/values/styles_widgets.xml`，收敛为 `Widget.ClassicWiki.Card.Filled|Outlined`、`Button.Filled|Tonal|Outlined|Text`、`Field.Outlined`、`Badge`、`SectionHeader`；
- Stage 1 **视觉应为零差异**（纯重构），用截图逐像素对比验证。

---

## 4. 可复用组件清单（Stage 1~2 建成）

| 组件 | 替代对象 | 说明 |
|---|---|---|
| `Md3ListItemView` | 设置页卡片内手写行、`view_setting_option_row` | 支持 one/two/three-line、leading icon/avatar、trailing value/chevron/switch，行高与热区走令牌 |
| `Md3SectionHeader` | 各页 `TextView` 标题 | 标签 + 可选操作 |
| `Md3EmptyStateView` | `view_list_state` | 图标 + headline + 说明 + 主按钮，支持加载/空/错误三态 |
| `Md3StatusBadge` | `bg_status_pill` + 各页手写 TextView | 语义色自动取 token（审核状态、未读、类型） |
| `Md3FormField` styles | 散落的 `TextInputLayout` | 统一 helper text / error / 计数器 / 必填标记 |
| `Md3BottomSheet` 基类 | 艺人选择、歌曲编辑、页码选择 | 统一 drag handle、圆角、inset、返回键行为 |
| `Md3Snackbar` 工具 | 33 处 Toast（仅操作类） | 支持 action（重试 / 查看 / 撤销） |
| `Md3SegmentedFilter` | Tab + 状态 Chip 叠加 | 一级分类用 Tab，二级筛选用 Chip，层级固定 |
| `Md3ProgressRow` | 上传/下载进度 | 线性进度 + 标题 + 百分比 + 可取消 |
| `Md3NavShell` | 现有 `nav_shell` + 模糊层 | 底栏样式（悬浮/贴底）继续保留，仅令牌化与动效统一 |

---

## 5. 分阶段实施（每阶段独立可交付）

> 每阶段固定产出：**变更文件清单 / `assembleDebug + testDebugUnitTest + lintDebug` 结果 / 改造前后截图 / 待解决问题**。
> 每阶段单独 commit，出问题可整段回滚。

### Stage 0 · 基线与截图工具（阻塞）
- 目标：采集 8 张基线截图（主界面 4 目的地 + 设置首页 + 2 个二级页 + 登录页）；
- **实际状态：阻塞**。`adb devices` 为空，SDK 未安装 emulator，本环境无法驱动 Android Studio 预览；
- 替代方案：以「值等价映射表」验证零视觉差异；设备接入后补齐 Baseline 并回填本文件。
- **不改代码。**

### Stage 1 · 令牌层（已完成，视觉零差异）

**变更文件**

| 文件 | 变更 |
|---|---|
| `res/values/tokens.xml`（新增） | 形状阶 7 档、语义别名 5 个、密度 8 个、海拔 4 个 |
| `res/values/integers.xml` | 新增 11 个动效时长令牌（含 `motion_control=220`、`motion_destination=300`，与改造前常量一致） |
| `res/values/themes.xml` | `Widget.ClassicWiki.TextInput` 四角改用 `shape_field`；新增 `Widget.ClassicWiki.Card.Filled/Elevated/Outlined` |
| `MotionManager.java` | 删除 `BASE_DESTINATION_MS` / `BASE_CONTROL_MS` 硬编码，改读 `R.integer.motion_*` |
| `layout/activity_settings.xml` | 6 张卡片改用 `Widget.ClassicWiki.Card.Filled`，移除重复的 `app:cardCornerRadius` |
| `layout/view_setting_option_row.xml` | `56dp` → `item_height_one_line`；`24dp` → `icon_md` |
| `layout/view_setting_switch_row.xml` | `56dp` → `item_height_one_line` |
| `layout/item_wiki_field.xml` | `88dp` → `field_label_width` |

**零视觉差异证明（值等价，非目测）**

| 原值 | 令牌 | 实际取值 |
|---|---|---|
| TextInput 四角 `12dp` ×4 | `shape_field` | `shape_md` = 12dp |
| 卡片 `cardCornerRadius=@dimen/card_corner_radius` | `shape_card` | 别名指向同一 dimen，手机 16dp / sw600dp 20dp 不变 |
| 设置行 `minHeight=56dp` | `item_height_one_line` | 56dp |
| 箭头图标 `24dp` | `icon_md` | 24dp |
| 字段标签列宽 `88dp` | `field_label_width` | 88dp |
| 动效 `300ms` / `220ms` | `motion_destination` / `motion_control` | 300 / 220 |

**构建结果**：`assembleDebug` + `testDebugUnitTest` + `lintDebug` **BUILD SUCCESSFUL（36s）**。

**未做**：原计划的 `Md3ListItemView` 等组件空壳。原因：无人使用的空壳属于死代码，改为在 Stage 2 首次落地时随用随建。

**待解决**：截图对比无法在本环境生成（无设备、无 emulator、无法驱动预览），需设备接入后补 Stage 0 基线。

### Stage 2 · 设置模块（已完成）

**变更文件**

| 文件 | 变更 |
|---|---|
| `values/attrs.xml`（新增） | `Md3ListItemView` 的 5 个 XML 属性（标题/说明/取值/前导图标/尾部形态） |
| `layout/view_md3_list_item.xml`（新增） | 单行/双行列表行结构，尾部三态（取值+chevron / chevron / 开关） |
| `Md3ListItemView.java`（新增） | 组件实现：`setTitle` / `setSupporting` / `setTrailingValue` / `setTrailingMode` / `setTrailingSwitch`，可点击时才挂水波纹，禁用时下发到子控件 |
| `layout/activity_settings.xml` | **6 张大卡片 → 3 个分组 + 6 个列表行**（通用 / 账号与提醒 / 其他），入口与跳转目标不变 |
| `SettingsActivity.java` | 摘要从 `TextView.setText` 改为 `Md3ListItemView.setSupporting` |
| `SettingsRows.java` | `addOptionRow` / `addSwitchRow` / `refreshValue` 全部改用新组件，**一处改动覆盖 6 个二级页** |
| `layout/activity_settings_display.xml` | 4 个静态开关改为列表行（双行 + 尾部开关） |
| `DisplaySettingsActivity.java` | 开关从 `findViewById` 改为 `switchOf(rowId)` 取行内开关 |
| `layout/activity_settings_audit.xml` | 后台提醒开关改为列表行 |
| `AuditSettingsActivity.java` | 同上，取行内开关并补空值保护 |
| `layout/view_setting_option_row.xml` / `view_setting_switch_row.xml` | **删除**（职责被 `Md3ListItemView` 取代） |

**行为保持**

外观页全部行由 `SettingsRows` 生成，自动获得新样式；账号 / 通知 / 关于不是列表页，未改动。单选项弹窗、轮询 Slider、保活入口、开关持久化逻辑均未变。

**构建结果**：`assembleDebug` + `testDebugUnitTest` + `lintDebug` **BUILD SUCCESSFUL（35s）**。

**踩坑记录**：`attrs.xml` 的 enum 值 `switch` 会生成同名 id 资源，而 `switch` 是 Java 关键字，AAPT 直接报 `not a valid resource name`。已改为 `trailing_switch`。

**未做**：`Md3SectionHeader` / `Md3FormField` 未单独建类 —— 分组标题就是 `FieldLabel` 样式的 `TextView`，表单字段已由 `Widget.ClassicWiki.TextInput` 统一，再包一层只会增加封装成本。

**待解决**：截图仍无法生成（无设备 / 无 emulator），需要在真机上确认 72dp 双行行高在 1.3x 字体下不截断。

### Stage 3 · 歌曲百科页（已完成）

**变更文件**

| 文件 | 变更 |
|---|---|
| `layout/view_wiki_query.xml` | 头卡改用统一 Card 样式；4 个操作按钮建立层级（1 填充 + 2 tonal + 1 文本）；历史容器改为 `HorizontalScrollView + ChipGroup` |
| `WikiQueryView.java` | 贡献记录从「卡中卡」改为 `Md3ListItemView` + `MaterialDivider`；查询历史从 5 条通栏描边按钮改为最多 8 个横向 Chip；区块卡片仍用 `surfaceContainerLow` + `shape_card` |

**改动理由**

- **按钮层级**：原先 4 个按钮都是描边样式，主操作「打开歌曲页」淹没在同等权重的按钮里；现在按 MD3 的 1 主（填充）/ 2 次（tonal）/ 1 低（文本）分层。
- **卡中卡**：每条贡献记录原本是一个内嵌 `MaterialCardView`，与外层区块卡形成双层容器，且每条多占 16dp 内边距；改为列表行后层级和密度都回归正常。
- **历史占位**：5 条通栏按钮纵向接近 200dp，把空态内容压到屏幕下半部；Chip 行只占一行高度。

**未做（有意）**

- `Md3EmptyStateView`：空态里还嵌着查询历史 Chip 行，抽成通用空态组件反而不合用；错误态的「图标 + 文案 + 重试」已符合 MD3，暂不额外包一层。
- Toast → Snackbar：MainActivity 的底栏是覆盖在内容之上的悬浮层，Snackbar 默认落在窗口底部会被底栏遮住，需要额外的 anchor/inset 处理；在拿到真机前不做这次替换，避免出现「提示被挡住」的回归。
- 字段列表保留「标签列 + 取值」两列结构：7+5 个字段若改成「取值 + 标签」的双行列表，纵向长度会翻倍，扫描效率反而下降。

**构建结果**：`assembleDebug` + `testDebugUnitTest` + `lintDebug` **BUILD SUCCESSFUL（38s）**。

**待解决**：无设备，无法确认按钮层级与 Chip 行在 320dp 窄屏下的换行/截断表现。

### Stage 4 · 我的贡献页（已完成，结构未动）

**变更文件**

| 文件 | 变更 |
|---|---|
| `layout/activity_main.xml` | 通道 / 账号卡改用 `Card.Elevated`、搜索卡改用 `Card.Filled`；分页器 6 个控件与搜索日期行改用 `touch_target_min` / `icon_md` / `item_height_one_line`；底栏圆角改用 `shape_nav` |
| `layout/item_contribution.xml` | 卡片改用 `Card.Elevated`；封面 `56dp` → `list_cover_size` |
| `ContributionAdapter.java` | 未读描边宽度改用 `stroke_emphasis` 令牌，不再手算 density |
| `values/tokens.xml` | 新增 `list_cover_size`（56dp）、`stroke_emphasis`（1.5dp） |
| `values/themes.xml` | `Widget.ClassicWiki.PageStep` 最小高度 40dp → 48dp（达到 MD3 触控下限） |

**按要求保持不动的部分**

- 通道 / 账号 / 搜索三张卡片的位置、显隐开关、内容结构完全不变；
- 分页器仍常驻，交互未改；
- 贡献卡仍是「封面 + 标题 + 艺人 + 状态 + 类型 + 时间」，未新增左侧色条（避免改结构，且「新」胶囊 + 描边已构成未读标识）。

**唯一的可见变化**：分页「上一页 / 下一页」按钮从 40dp 提升到 48dp，视觉上整条分页器略高 8dp，但换来了符合规范的触控热区。

**构建结果**：`assembleDebug` + `testDebugUnitTest` + `lintDebug` **BUILD SUCCESSFUL（37s）**。

**待解决**：无设备，未验证未读描边在深色主题下的对比度，以及 48dp 分页器在 320dp 窄屏下是否挤压页码。

### Stage 5 · 上传 / 下载（部分完成）

盘点后发现上传页**本来就已经分成 4 张卡片**（目标艺人 / 专辑图片 / 专辑信息 / 曲目），计划里的「切成 4 个 section」实际不存在；真正的问题是**校验失败只弹 Toast**，以及「专辑信息」卡片过长。因此本阶段把重点放在表单状态上。

**已完成**

| 文件 | 变更 |
|---|---|
| `AlbumUploadView.java` | `validate()` 额外记录出错字段 id（校验文案与判定顺序完全不变）；提交失败时滚动定位到该字段，文本字段用 `TextInputLayout.setError` 显示行内错误并聚焦；用户开始输入即自动清除错误 |
| `layout/view_soundcloud.xml` | 4 张卡片统一为 `Widget.ClassicWiki.Card.Filled`；下载 / 重试 / 传入按钮、3 个勾选项、格式开关的 48dp 全部改为 `touch_target_min` |

**行为影响**

- 校验通过的路径、提交参数、上传流程零改动；
- 失败时的文案与之前完全一致，只是从「底部状态栏 + Toast」升级为「定位到字段 + 行内错误」；
- 无具体字段的错误（如未登录）仍走原来的 Toast 兜底。

**未做（需真机验证后才能动）**

- 逐曲下载进度行（`Md3ProgressRow`）：`SoundCloudDownloadView` 的 set 下载是多线程 + 顺序回调，改成列表行需要动下载状态机；没有设备无法验证失败重试、取消、曲序，风险高于收益。
- 「专辑信息」卡片再拆成两张：纯 XML 手术，但会改变长表单的滚动节奏，需要目测确认。
- Snackbar：底栏遮挡问题同 Stage 3。

**构建结果**：`assembleDebug` + `testDebugUnitTest` + `lintDebug` **BUILD SUCCESSFUL（33s）**。

**待解决**：无设备，未验证行内错误在键盘弹起时是否被输入法遮挡（已用 `requestRectangleOnScreen` 主动滚动，但需要实测）。

### Stage 6 · 主框架、导航、Insets（部分完成）

**已完成**

| 文件 | 变更 |
|---|---|
| `values/dimens.xml` | 底栏图标 22dp → 24dp |
| `values/themes.xml` | 选中指示器 46×26 → 56×28dp；item 上下内边距 6/8 → 4/6dp，保持 56dp 总高 |
| `NavItemAnimator.java` | 删除 `ANIM_MS=240`，改用 `MotionManager.controlDuration()`（`motion_control` 令牌，220ms，仍随动效强度缩放） |
| `BaseActivity.java` | Insets 计算纳入 `displayCutout`：横屏挖孔设备不再出现内容被裁成窄条 |

**未做（都需要真机才能确认没改坏）**

- `enableEdgeToEdge()` 迁移：现有实现（`setDecorFitsSystemWindows(false)` + 透明系统栏 + 自己控制图标明暗）功能等价，迁移到 `EdgeToEdge.enable` 需要处理 `SystemBarStyle.auto` 的 Kotlin `Function1` 参数，收益是去掉两个废弃 API 调用，风险大于收益。
- 顶栏滚动抬升：需要给「我的贡献 / 百科 / 上传 / 下载」四个滚动容器分别挂 `OnScrollChangeListener`，再联动 toolbar 阴影；无设备无法确认滚动边界与回弹时的抖动。
- 分页器与底栏合并为同一外壳：属于视觉结构调整，必须目测。

**构建结果**：`assembleDebug` + `testDebugUnitTest` + `lintDebug` **BUILD SUCCESSFUL（37s）**。

**待解决**：24dp 图标 + 28dp 指示器 + 常显标签在 56dp 底栏内是否挤压；1.3x 字体下标签是否被裁。这两点必须真机确认，若挤压则回退到 22dp / 46×26。

### Stage 7 · 弹窗统一与无障碍（已完成可验证部分）

**已完成**

| 文件 | 变更 |
|---|---|
| `AccountSettingsActivity.java` | 最后一处原生 `AlertDialog.Builder`（退出登录确认）改为 `MaterialAlertDialogBuilder`，全应用 10 个对话框现在都用 MD3 样式 |
| `AlbumSongAdapter.java` | 拖拽把手在 `ACTION_UP` 主动 `performClick()`；拖拽必须在 `ACTION_DOWN` 起手，故对 `ClickableViewAccessibility` 做有注释的定点抑制 |
| `SquareCropView.java` | 补 `performClick()` 覆写并在 `ACTION_UP` 调用，自定义触控视图可被 TalkBack 识别 |
| `view_album_upload.xml` / `view_soundcloud.xml` / `view_wiki_query.xml` | 上传状态、下载状态、加载文案加 `accessibilityLiveRegion="polite"`；错误文案用 `assertive` |

**可验证的结果**：`lint-results-debug.txt` 中 `ClickableViewAccessibility`、`ContentDescription`、`TouchTargetSize` **均为 0 条**（改造前有 3 条）。

**未做**

- 艺人选择 / 歌曲编辑 / 页码选择改 BottomSheet：这三个都是带输入框与 RecyclerView 的交互容器，`BottomSheetDialog` 的键盘避让、拖拽关闭、返回键行为必须真机验证；当前 `MaterialAlertDialogBuilder` 本身就是 MD3 组件，不构成样式错误。
- TalkBack 焦点顺序走查：必须真机 + 读屏器，无法静态完成。

**构建结果**：`assembleDebug` + `testDebugUnitTest` + `lintDebug` **BUILD SUCCESSFUL（33s）**。

**待解决**：无设备，读屏器实测待补。

### Stage 8 · 大屏与 Expressive（已完成可静态验证部分）

**已完成**

| 文件 | 变更 |
|---|---|
| `MaxWidthLinearLayout.java`（新增） | 按 `content_max_width` 收敛测量宽度，超出时居中。手机宽度小于阈值，测量规格不变，**手机端零影响** |
| `activity_settings.xml` / `activity_main.xml`（贡献页） / `view_wiki_query.xml` / `view_album_upload.xml` / `view_soundcloud.xml` | 五个主页面内容容器换成 `MaxWidthLinearLayout` + `layout_gravity=center_horizontal` |
| `view_wiki_query.xml` / `view_list_state.xml` | 空态标题 `TitleMedium` → `HeadlineSmall`（Expressive 字号强调）；加载 / 错误文案补 live region |

`content_max_width` 这个此前「声明了但从未被引用」的令牌现在真正生效：手机 640dp、sw600dp 840dp，平板上一行文字不会横跨整屏。

**未做**

- `layout-sw600dp` 列表-详情双栏：贡献页拆双栏要改 RecyclerView 与详情交互，属于结构性改动，必须真机 + 平板验证。
- 按钮组 / 形状 morph：Expressive 官方组件需要 material 1.13+，按既定决策不升级依赖，靠令牌模拟的部分（字号强调、形状阶、动效令牌）已在前几个阶段落地。

**构建结果**：`assembleDebug` + `testDebugUnitTest` + `lintDebug` **BUILD SUCCESSFUL（35s）**。

**待解决**：无平板设备，未验证 840dp 下居中留白是否合适。

### Stage 9 · 对照参考 APK 的可视化外观（Stage 1–8 之后追加）

**背景**：用户反馈「变化不大」。原因判定：Stage 1–8 以令牌化与结构语义为主，视觉上刻意保持零差异；参考 APK（Deadliner / InstallerX Revived）的观感差异主要来自 **彩色图标底托、色卡预览、分段控件、色点选择器与更细的外观参数**，而非圆角与配色本身。

**已完成**

| 文件 | 变更 |
|---|---|
| `view_md3_list_item.xml` | 前导图标从裸 `ImageView` 改为 40dp 圆角 `MaterialCardView` 底托，内部 22dp 图标居中 |
| `Md3ListItemView.java` | 新增 `TILE_NONE/PRIMARY/SECONDARY/TERTIARY`，按 `colorXxxContainer` + `colorOnXxxContainer` 成对染色；`refreshLeadingTile()` 支持不重建页面即时刷新；行高按密度缩放 |
| `attrs.xml` / `tokens.xml` | `md3IconTileColor` 枚举 + `icon_tile_size` / `icon_tile_icon_size` |
| `activity_settings.xml` | 六个入口分别配主 / 次 / 辅色底托 |
| `activity_settings_appearance.xml` | 重写：**实时预览卡**（主/次/辅/容器四张色卡 + 示例卡片与状态胶囊）、主题模式 **分段按钮**、显示大小 **滑杆**、其余设置行带彩色底托图标 |
| `AppearanceSettingsActivity.java` | 主题分段按钮、字号滑杆（0.85–1.30 / 步长 0.05，松手落盘并重建）、**带色点的调色板弹窗**（动态取色用三色渐变圆点）、列表密度、图标底托开关 |
| `item_palette_option.xml` / `bg_status_pill.xml`（新增） | 调色板色点行、状态胶囊 |
| `AppearanceManager.java` / `WikiPrefs.java` | 新增 `iconTilesEnabled`、`densityScale`（0.88 / 1.0 / 1.12）与对应偏好键 |

**新增外观设置**：调色板色点预览、显示大小滑杆（10 档）、列表密度（紧凑/标准/宽松）、彩色图标底托开关。

**构建结果**：`assembleDebug` + `lintDebug` **成功**，APK 7,350,200 字节；lint 无 Error（仅存量 UnusedResources / Overdraw 警告）。

**Stage 9.1 · 排版与栅格对齐参考应用（用户反馈「布局和排版差别大」后追加）**

| 维度 | 改动 |
|---|---|
| 顶栏标题 | `TitleLarge`(22sp) → `HeadlineSmall`(24sp)，更接近参考页的大标题 |
| 分组标签 | 新增 `TextAppearance.ClassicWiki.GroupLabel`（TitleSmall 14sp + onSurfaceVariant）；外观页的「外观基础 / 交互与动效」从卡片内标题移到**卡片外**，与一级设置页一致 |
| 分割线 | 删除设置列表内的全部 `MaterialDivider`（含动态注入与静态 XML），改用行高 + 内边距形成留白 |
| 行高 | 双行 72dp → **80dp**，三行 88dp → 96dp |
| 行内边距 | 16dp → **20dp**（新令牌 `settings_row_inset`） |
| 行标题 | BodyLarge(16sp regular) → **TitleMedium(16sp medium)** |
| 卡片圆角 | 16dp → **24dp**（含 sw600dp 覆盖） |
| 图标底托 | 40dp → **36dp**，图标 22dp → 20dp，右侧间距 16dp → 12dp，为文字列腾出宽度 |
| 文案 | 外观页所有说明压缩到一行可读长度（原先两行截断、与右侧取值挤在一起） |
| 返回箭头 | `setSupportActionBar` 会清空 XML 的 navigationIcon，改由 `BaseActivity#bindToolbar` 显式补回 |

**Stage 9.2 · 对齐 M3 Expressive 的观感（联网检索 m3.material.io / Android Developers 后追加）**

检索结论（m3.material.io「Start building with M3 Expressive」、Typography 2025-05 更新、Buttons 更新）：Expressive 的可感知特征集中在四类——**emphasized 字号层级、更大的形状阶与圆角、更厚的滑杆轨道、带图标的开关/选中态**；新组件（ButtonGroup、FAB Menu、LoadingIndicator、形状 morph）需要 material 1.13+，本项目 1.11.0 无法直接使用，因此全部用现有控件模拟：

| 维度 | 改动 |
|---|---|
| 滑杆 | 新增 `Widget.ClassicWiki.Slider.Expressive`：14dp 厚轨道、7dp 轨道圆角、11dp 拇指、容器色 halo；用于「显示大小」与「轮询周期」 |
| 开关 | 新增 `Widget.ClassicWiki.Switch` 并挂到主题 `materialSwitchStyle`：选中态拇指内显示勾选 |
| 弹窗 | `materialAlertDialogTheme` 换为 28dp 圆角的 overlay（Expressive 大圆角） |
| 顶栏 | 标题 `HeadlineSmall` → **HeadlineMedium 28sp**，`actionBarSize` 56 → **64dp** |
| 取色 | 调色板从「弹窗列表」改为**圆形色点横向条**：动态取色是三色渐变圆，选中项 3dp 描边 + 勾选（对齐参考应用的色点条） |
| 图标 | 「彩色图标底托」默认关闭：关闭时回到 **24dp 裸线图标**（仅 20dp 内边距 + 16dp 间距，与参考列表一致）；开启才显示 36dp 分类色块 |
| 色点弹窗 | 删除 `item_palette_option.xml`（被色点条取代） |

**未做（依赖限制）**：调色板样式（TonalSpot / Vibrant / Expressive / Neutral / Rainbow / FruitSalad / Monochrome / Fidelity / Content）。这些变体需要 material-color-utilities 生成整套 32 个色彩令牌，而 View 体系没有运行时覆写主题属性的公开 API；仅靠 1.11 的静态主题分支无法真实实现，故未做「假选项」。

**待验证**：分段按钮在 1.3x 字体下三行文案是否被压缩；色点条在小屏的横向滚动；裸图标与 64dp 顶栏在长标题页的表现。

**Stage 10 · 构建链升级到真正支持 Expressive 的组件（外观页优先）**

构建链：

| 项 | 变更 |
|---|---|
| AGP | 8.1.2 → **8.6.1** |
| Gradle | 8.7（保持不变，AGP 8.6 最低要求） |
| compileSdk | 34 → **35**（SDK 35 已安装；targetSdk 仍为 34，避免行为变更） |
| material | 1.11.0 → **1.13.0** |
| API 迁移 | 1.13 移除了 `material.R.attr.colorPrimary/colorError` 的重复声明，6 处改用 `androidx.appcompat.R.attr.*` |

外观页：

| 项 | 变更 |
|---|---|
| 主题模式 | 分段按钮 → **三张卡片式单选**：选中卡填充 `colorPrimaryContainer`、文字切 `onPrimaryContainer`、单选圈同步染色；卡片 20dp 圆角、卡间 8dp |
| 滑杆 | 使用 1.13 新增的 Expressive 属性：`trackHeight 16dp`、`thumbWidth 4dp`、`thumbHeight 32dp`、`thumbTrackGapSize 6dp`、`trackStopIndicatorSize 4dp` |
| 字号档位 | 松手吸附到 **5%** 整数档（85/90/…/130），显示值同步取整 |
| 滚动位置 | `NestedScrollView` 增加 id + `onSaveInstanceState` 保存 `scrollY`，重建后恢复；`reloadSelf()` 延后到下一帧执行，避免回调中同步重建 |
| 图标 | 新增 8 个可复用图标：`ic_dark_mode`、`ic_vibration`、`ic_motion`、`ic_density`、`ic_tune`、`ic_contrast`、`ic_dashboard`、`ic_text_fields`；外观页各行改用语义图标 |
| 色彩令牌 | 由 `Gen.java` 调用 material 内置 HCT（SchemeVibrant / SchemeExpressive / SchemeNeutral）生成 **9 种子 × 3 方案 × 明暗 = 54 组令牌、27 个主题分支**；UI 新增「色彩风格」FilterChip（标准 / 鲜明 / 表现 / 中性），真实切换整套 32 个色彩令牌 |

生成文件（勿手改）：`values/palettes_variants.xml`、`values-night/palettes_variants.xml`、`values/themes_variants.xml`、`values/strings_variants.xml`、`java/.../GeneratedPalettes.java`。

**已知限制**：动态取色（跟随壁纸）只有系统一套方案，选中时「色彩风格」芯片置灰。

**Stage 11 · 外观页缺陷修复与「独立卡片」列表样式**

| 问题 | 根因 | 修复 |
|---|---|---|
| 主题模式选中圈总是回到「跟随系统」 | 系统已是深色时，从「跟随系统」切到「深色」实际明暗不变，AppCompat 不重建页面，选中态没刷新 | 切换后主动 `post(renderThemeCards)`，并在 `onResume` 重新同步 |
| 深色模式开关没有勾选 | `values-night/themes.xml` 是同一 style 名的独立定义，不会继承浅色分支的 item，漏了 `materialSwitchStyle` | 夜间主题补齐 `materialSwitchStyle` / `materialAlertDialogTheme` / `actionBarSize` / `windowAnimationStyle` |
| 跟随壁纸时另外 3 个方案不可用 | 芯片被禁用 | 芯片始终可用；在壁纸模式下选择方案时，取**当前壁纸主色**最近的种子色套用该方案，并提示实际切换结果 |
| 壁纸 + 方案切回壁纸后状态丢失 | 方案被编码在调色板键里，切回 dynamic 就丢了 | 方案拆成独立偏好 `KEY_SCHEME_VARIANT`，切回壁纸仍保留用户选的方案 |
| 设置项分隔/圆角与参考页差距 | 所有行在一张大卡里 | 新增「设置项样式」选择：独立卡片模式每行外包 **12dp 圆角卡、6dp 间距**，外层分组卡透明化并把自身圆角归零（避免裁掉首/末行卡角）；行卡与页面其他卡同宽 |
| 独立卡片开关不好点 | 开关位于独立卡片内部，且只有开关本体响应 | 样式选项改为「外观基础」卡内的**行式单选**（整卡分组 / 独立卡片），不再依赖独立卡片内的开关 |
| 图标不够 Expressive | 彩色底托默认关闭 | 默认开启彩色图标底托（Material Symbols 的「图标 + shaped container」表现），图标统一用 on-container 前景色 |
| 分组标题层级偏弱 | 灰色 14sp | 改为强调色 + 加粗的 TitleSmall |

**Stage 12 · 分组圆角规范 / 水波纹裁剪 / 字体收窄**

| 项 | 变更 |
|---|---|
| 分组圆角（后续标准） | 首卡上两角、末卡下两角 = **24dp**；接缝处 = **8dp**；卡间距 = **4dp**。首末完全对称，接缝角更小 |
| 水波纹 | 行内 `selectableItemBackground` 是矩形，给行卡加 `clipToOutline + clipChildren`，按下高亮按圆角边界裁剪 |
| Spotify Mix | 从全局标题/分组标签/设置行/主题卡移除；只保留在**贡献卡片**（`tv_name`/`tv_artist`）、**歌曲百科信息**（`tv_song_name`/`tv_song_artist`/`tv_field_value`）、**声云封面资源信息**（`tv_sc_title`/`tv_sc_artist`/`tv_sc_cover_source`）。中文由系统字体回落到 |
| Material Symbols | 早期 zip 只有分类预览大图；后续提供的 20 个社区包里含**单图标 24×24 SVG**，已按此批量替换（见 Stage 13） |

**Stage 13 · 图标全量换成 Material Symbols Rounded**

| 项 | 变更 |
|---|---|
| 资源来源 | `D:\Download\Material Symbols (Community)*.zip` 共 20 个包、3859 个单图标 SVG（覆盖 10k–zoom_out 全表） |
| 转换 | `convert.py` 解析 `<path d>`，`fill-rule=evenodd` → `android:fillType="evenOdd"`，统一输出 `24dp/24dp`、viewport 24、`fillColor="#FFFFFFFF"` 的 `<vector>`，全部由 `ImageViewCompat.setImageTintList` / `app:tint` 着色 |
| 覆盖范围 | 现有 **40** 个 `ic_*` 全部替换（含底栏 4 个、设置图标托、百科页、贡献卡、上传/下载、翻页与拖拽手柄） |
| 特例 | 社区包无 `vibration`、`account_circle` 等 9 个名称，改由其余包补齐；仅 `vibration` 缺失，改用语义最近的 `mobile_vibrate` |
| 体积 | 已删除误拷入的 `assets/fonts/material_symbols_rounded.ttf`（15.2MB）——本方案走矢量而非字体内联，APK 回到 9.53MB |

「独立卡片」样式目前作用于外观页的「交互与动效」分组（`SettingsRows.addOptionRow(... , asCards)` 已支持逐行切换，后续可推广到其他设置页）。

**Stage 14 · 自绘 Expressive 底栏 + 剩余二级页归位**

底栏（`ExpressiveNavBar`，自绘 `ViewGroup` 取代 `BottomNavigationView`）：

| 项 | 变更 |
|---|---|
| 选中底板 | 从「只罩图标的小 pill」改为包裹「图标 + 文字」的大圆角底板；圆角取 `min(半高, 20dp)` |
| 描边 / 填充双态 | 4 组图标各备份 outlined 与 filled 两版，选中时 120ms 交叉淡入淡出并加粗文字 |
| 可拖动指示器 | `onInterceptTouchEvent` 超过 touchSlop 即接管；拖动中底板跟手、跨格触发 `HapticsManager.swipe` 预览；松手吸附到最近标签 |
| 尺寸 | 悬浮态左右收窄到 20dp、圆角 28dp、条目高 60dp |
| 顶部被裁 | 悬浮态下外边距 = `navigationBars.bottom + 8dp`；贴底态把 `max(systemBars, mandatorySystemGestures)` 折算成底栏自身高度与内边距（`MaterialCardView` 的 padding 不参与 wrap_content 高度计算，不能加在卡片上） |
| 透明适配 | 4 个目的地页的滚动都接上 `requestOverlayBlurUpdate`，玻璃底栏在每个页面都随内容重采样 |
| 串页修复 | `switchDestination` 的 `switched` 只比较目的地；并新增 `hidePagesExcept` 与「无过渡」分支，避免 `outgoing == incoming` 时把当前页自己淡到 INVISIBLE |
| 顶栏图标 | 菜单项统一 `setIconTintList(colorOnSurfaceVariant)`，浅色主题不再出现白图标看不见 |

剩余二级页（账号 / 通知 / 关于）与登录页：

| 项 | 变更 |
|---|---|
| 账号与凭据 | 由「两段文字 + 两个通栏按钮」改为分组列表：账号行（昵称 + 用户 ID，chevron → 登录页）、退出登录行（未登录时整行置灰）；新增「凭据」说明卡 |
| 通知 | 权限状态改为「图标底托 + 标题 + 说明 + 语义色取值」行；测试与系统设置改为可点击行 |
| 关于 | 能力说明保留正文卡；版本号改为只读取值行 |
| 登录页 | 二维码容器由直角 `FrameLayout` 改为 240dp 的 28dp 圆角 `MaterialCardView`（`colorSurfaceContainerHigh`） |
| 组件补强 | `Md3ListItemView` 新增 `TRAILING_VALUE_PLAIN`（只读取值，不带 chevron）与 `setTrailingValueColor`（错误态语义色） |

**Stage 15 · 设置模块全部二级页收口**

| 页面 | 变更 |
|---|---|
| 后台审核提醒 | 开关行补前导图标；「后台保活」由 `SettingsRows` 运行时注入改为 XML 声明的两行标准列表项（带图标底托、取值与分隔线），说明文字移到卡片下方作脚注 |
| 后台审核提醒 | 卡片材质统一为 `Widget.ClassicWiki.Card.Filled`（原来混用 `?attr/materialCardViewFilledStyle` + 手写圆角） |
| 显示 | 四个开关行 / 选项行全部补前导图标（封面 / 通道卡 / 账号卡 / 搜索 / 搜索布局），与账号、通知页一致 |
| 账号·通知·关于·登录 | 见 Stage 14 |

至此设置模块（一级目录 + 外观 + 显示 + 账号 + 通知 + 后台审核 + 关于）的所有条目均为
「图标底托 + 标题 + 辅助说明 + 尾部取值/开关/chevron」的统一行结构，不再有独立的通栏按钮或裸文字状态。

**Stage 16 · 大屏宽度约束 + 选择型弹窗 BottomSheet 化**

| 项 | 变更 |
|---|---|
| 大屏适配 | 显示 / 后台审核 / 通知 / 账号 / 关于五个二级页的滚动内容由 `LinearLayout` 换成 `MaxWidthLinearLayout`（`layout_gravity=center_horizontal`）。手机宽度小于 `content_max_width`（640dp）时测量规格不变，零回归；平板 / 展开态折叠屏上内容居中、两侧留白 |
| 艺人选择面板 | `ArtistPickerDialog` 从 `MaterialAlertDialogBuilder` 改为 `BottomSheetDialog`：新增 `BottomSheetDragHandleView` 拖拽把手、`TitleLarge` 标题，`setSkipCollapsed(true)` + `STATE_EXPANDED`，保留原搜索 / 建议 / 空态 / 提交逻辑与线程取消（`executor.shutdownNow()` 挂在 dismiss 上） |
| 分层原则 | 对话框继续承载确认 / 破坏性操作（退出登录等）；搜索与选择类改为底部抽屉，符合 M3 Expressive 的模态层级 |

**仍待处理**：`layout-sw600dp` 未新增（当前靠 `MaxWidthLinearLayout` 约束行长）；Toast 收口与其余选择型弹窗见 Stage 18 / 19。


**Stage 17 · 「独立卡片」行样式推广到全部设置页**

| 项 | 变更 |
|---|---|
| 新增能力 | `SettingsRows.applyRowStyle(LinearLayout)`：按「设置项样式」偏好，把 XML 里静态声明的分组就地重排 —— 独立卡片模式隐藏 `MaterialDivider`、把每个 `Md3ListItemView` 包进 8dp 圆角卡（卡间距 4dp、无海拔、轮廓裁剪），外层分组卡改透明 + 直角（否则 24dp 圆角会裁掉首/末行自己的角），说明文字补回 20dp 内边距与行文字列对齐 |
| 适用页面 | 设置一级目录（通用 / 账号与提醒 / 其他）、显示、后台审核提醒（功能开关 / 后台保活）、通知（状态 / 测试与跳转）、账号与凭据 |
| 关于页 | 版本行从「说明卡内的最后一行」拆成独立分组卡，同样跟随该偏好 |
| 细节 | 行从 `LinearLayout` 迁入 `MaterialCardView`（FrameLayout 体系）时，先把 `LinearLayout.LayoutParams` 换成 `FrameLayout.LayoutParams`，避免测量阶段类型转换崩溃 |
| 返回同步 | 外观页可修改该偏好；设置一级页在 `onResume` 检测到偏好变化时 `recreate()`，避免回退后仍显示旧结构 |
| 圆角规范 | `applyGroupedCorners`：首卡上两角 / 末卡下两角 24dp，接缝角 8dp —— 与外观页一致 |

至此 7 个设置页面的所有设置行都遵循同一套「整卡分组 / 独立卡片」切换标准。

**Stage 18 · Toast 全量收口到 Snackbar（`Feedback`）**

| 项 | 变更 |
|---|---|
| 统一通道 | 新增 `Feedback`：`show` / `showLong` / `action` 三个入口，锚点统一取 `android.R.id.content`（页面淡入淡出时不丢），提示渲染为 M3 Snackbar |
| 底栏避让 | 主界面有悬浮 `bottom_overlay` 时，把 Snackbar 的 `bottomMargin` 抬高「底栏高度 + 8dp」，避免被玻璃胶囊遮住；启动首帧底栏尚未测量（高度为 0）时挂一次性 `OnGlobalLayoutListener`，等测量完再抬，否则「已恢复上次查询」这类启动提示会直接压在底栏上 |
| 迁移范围 | 设置各页、百科查询、歌曲下载、专辑上传、封面裁剪、艺人选择、外链打开全部改走 `Feedback` |
| 例外 | 登录成功后立即 `finish()`，Snackbar 会随窗口消失，保留 Toast；`WikiLinks` 拿不到 Activity 上下文时同样退回 Toast |
| 重建兼容 | 外观页部分设置改完会 `recreate()`，提示文案暂存到静态字段，由新页面的 `onCreate` 补显示；1 秒内没有重建则作废 |

**Stage 19 · 其余选择型弹窗 BottomSheet 化**

| 项 | 变更 |
|---|---|
| 新增组件 | `SheetList`（`dialog_sheet_list.xml`）：拖拽把手 + `TitleLarge` 标题 + 内部可滚动列表，列表最高占屏幕 6 成，超出后抽屉内滚动；提供「单选列表」与「普通操作列表（可带底部动作）」两种形态 |
| 页码选择 | 我的贡献页码由 `AlertDialog.setSingleChoiceItems` 改为 `SheetList.singleChoice`，选中项保留 radio 选中态 |
| 音轨 / 碟号 | 上传页 `pickNumber`（最多 99 项）改用抽屉，避免超长对话框 |
| 未读消息 | 顶栏消息入口改用 `SheetList.actions`，「全部标记已读」沉到抽屉底部动作区 |
| 保留对话框 | 退出登录、移除歌曲等确认 / 破坏性操作仍用 `MaterialAlertDialogBuilder` |

**缺陷修复 · 顶栏未读角标消失**

| 项 | 说明 |
|---|---|
| 现象 | 有未读审核消息时，顶栏铃铛没有角标；且切过一次底部导航后必定消失 |
| 根因 | `updateMessageBadge` 用布尔 `badgeAttached` 记住「已挂过」。`switchDestination()` 每次都会 `invalidateOptionsMenu()`，菜单清空重建后 ActionView 是新对象，旧角标仍挂在已脱离窗口的旧视图上，且因标记为已挂载而不会重新挂载 |
| 修复 | 改为记录锚点对象 `badgeAnchor`，每次刷新比对当前 `toolbar.findViewById(R.id.action_messages)`；锚点变化时先 `detachBadgeDrawable` 再重新 `attach`。`onPrepareOptionsMenu` 会在菜单重建后触发刷新，角标随之回到新锚点 |
| 验证 | 真机（1216×2640）：预置 2 条未读 → 角标显示 2；切到「我的贡献」（触发菜单重建）后仍为 2；点「全部已读」后角标消失 |

**仍待处理**：`layout-sw600dp` 未新增（当前靠 `MaxWidthLinearLayout` 约束行长，登录页的两个通道容器也已接入）。

**Stage 20 · 主题模式接入行样式 + 底栏合并态投影 + 未通过原因归档**

| 项 | 变更 |
|---|---|
| 主题模式 | 三张主题卡从「基础」大卡里拆出成独立分组（`card_theme_group`），跟随 `rowsAsCards`：整卡分组时无间隙、首末 24dp 圆角、未选中不描边；独立卡片时分组卡透明、每张 8dp 圆角 + 4dp 间距 + 1dp 描边 |
| 底栏投影 | 页面选择器展开时底栏上圆角归零，原本为消除接缝把底栏投影也归零，导致切到「我的贡献」时阴影消失。改为由覆盖层整体（选择器 + 底栏）投影：`overlay_content` 自定义 `OutlineProvider`（按底栏外边距内缩、扣掉下外边距），`bottom_overlay` 关闭 `clipChildren` |
| 原因归档 | 新增 `NetEaseMsgClient`（WEAPI `/api/msg/private/history`，uid 79248480）+ `RejectArchive`（解析「拒绝原因：」与「咨询@曲库小学妹。」两种句式，按消息时间 + 原文哈希去重，原因分组）；贡献列表与后台轮询在出现未通过结果时触发同步，10 分钟限频 |
| 归档入口 | 不在底栏：设置 → 账号与提醒 →「未通过原因」（`RejectReasonsActivity`），按原因分组展示，点原因复制原因、点专辑复制通知原文，摘要行显示「已归档 N 条 · M 类原因」 |

真机验证（3B1F66EA9BBUDV2V / 1216×2640 / 476dpi，Stage 14 前完成）：

- 悬浮底栏：左右拖动可在 4 个标签间切换并吸附（`input swipe` 落在底栏 Y 区间内）；
- 贴底底栏：背景一直铺到屏幕最底边，逐像素采样底部 5 行颜色一致，不再漏出内容；
- 浅色 / 深色主题、玻璃模糊、账号/通知/关于/登录四页均按预期渲染；
- `assembleDebug` 与 `lintDebug` 均通过（仅 `BaseActivity` 使用过时 API 的提示）。

**Stage 21 · 一致性收口（外观分组卡 / 底栏滑块 / 选择器 / 日期控件）**

| 项 | 变更 |
|---|---|
| 底栏滑块抖动 | `ExpressiveNavBar.onLayout` 在 `dragging` 期间不再按「最近标签」重排底板。拖动跨过标签时 `applyItemStates` 改字重会触发一次 layout，此前会把底板吸到标签中心再被下一帧拉回手指位置，表现为「闪一下」 |
| 底栏滑块圆角 | 底板圆角由 20dp 提升到 28dp（与 `nav_float_corner_radius` 一致），高度不足时仍退化为半高胶囊 |
| 外观分组卡 | 「主题模式 / 调色板 / 显示大小 / 设置项样式」收进同一个 `card_appearance_group`：整卡分组时四个分区透明并合并成一张 24dp 卡；独立卡片时按「交互与动效」的分组标准处理——四张 `surfaceContainerHigh` 卡、间距 4dp、首卡上两角 24dp / 末卡下两角 24dp（`SettingsRows.applyGroupedCorners`）。主题模式改为左中右三列 `MaterialButtonToggleGroup` 分段选择（选中列 primaryContainer，图标 + 文字随 state_checked 换色），独立卡片上下留白收紧到 12dp（行式分区 4dp） |
| 关闭独立卡片 | `SettingsRows.applyRowStyle` 在整卡分组分支也隐藏 `MaterialDivider`，并把分组容器左右内边距统一为 `settings_row_inset`（20dp）、分组卡恢复标准填充卡与 24dp 圆角 |
| 消息空态 | 无未读时不再弹 `AlertDialog`，改用与列表态同一骨架的 `SheetList.message`（把手 + 标题 + 圆形底托图标 + 说明） |
| 日期控件 | 「我的贡献」的提交日期筛选从 `dateRangePicker` 改为与专辑上传页一致的 `datePicker` 两步（先开始、后结束），参数与 UTC 回填规则一致 |
| 页面选择器 | 上一页 / 下一页改为 `IconButton.Filled.Tonal` 圆形图标按钮（48dp），页码改为 primaryContainer 胶囊；新增 `ic_chevron_left`，移除旧 `PageStyle` 文字按钮样式 |
| SoundCloud 试听曲目 | `SoundCloudSetClient` 读取 `policy=SNIP` / 全 `snipped` 转码，标记 `Track.previewOnly` 与 `Set.previewCount`；批量下载跳过这些曲目并给出原因，不再把 lucida 的 `Cannot read properties of undefined (reading 'url')` 直接抛给用户 |



---

## 6. 关于「M3 Expressive」的边界与依赖决策

Material Views 的 Expressive 组件（FloatingToolbar、ButtonGroup、LoadingIndicator、形状 morph 等）需要 **material 1.13+**，当前是 1.11.0。

两条路径：

- **A（保守，推荐先做）**：不升级依赖，用令牌实现 Expressive 的「观感」——字号强调、形状阶、emphasized 动效曲线、按钮组（用 `MaterialButtonToggleGroup` 1.11 已支持）；
- **B（激进）**：升级到含 Expressive 的 material 版本，能拿到官方组件，但会带来默认样式/主题属性变更，需要一轮视觉回归。

建议：Stage 1~7 走 A；Stage 8 单独开分支试 B，若回归问题多则只保留 A。

---

## 7. 风险与回滚

| 风险 | 应对 |
|---|---|
| 布局回归（遮挡/溢出/横竖屏） | 每阶段截图对比 + 320dp 窄屏 + 1.3x 字体 + 平板三档验证 |
| 业务逻辑被误改 | UI 改造只动 layout / style / View 层；涉及 Java 时限定在渲染方法 |
| 主题属性变更导致深色异常 | 只新增令牌，不删旧样式；旧样式保留一阶段后清理 |
| 设备不可用导致无法截图 | 当前无设备、无 emulator、无法驱动预览：先用值等价映射与结构 diff 代替；设备接入后补截图回归 |
| 依赖升级风险 | 单独分支，先跑 `assembleDebug` + 全量截图回归 |

---

## 8. 验收标准（每阶段通用）

- [ ] `assembleDebug` / `testDebugUnitTest` / `lintDebug` 全绿；
- [ ] 功能路径与基线一致（点击、返回、旋转、进程重建、深色、动态取色）；
- [ ] 新增硬编码尺寸为 0（全部走令牌）；
- [ ] 触控热区 ≥48dp，正文对比度 ≥4.5:1；
- [ ] 系统栏与输入法无遮挡（API 26 / 30 / 34）；
- [ ] 提供变更文件清单 + 前后截图 + 待解决问题。

---

## 9. 已确认事项（原待确认清单）

1. **改造顺序**：已确认「设置模块 → 歌曲百科 → 我的贡献 → 上传/下载 → 主框架」。
2. **Expressive 依赖**：已确认先不升级 material，用令牌模拟。
3. **我的贡献顶部三卡**：已确认结构不动，只统一间距/圆角/字号/列表密度；通道卡片不移位。
4. **截图对比**：已确认用布局预览；但当前环境既无设备也无 emulator，且无法驱动 Android Studio 预览，**该交付项目前无法执行**，需等设备或本地预览能力补齐。

---

## 10. 下一步

进入 **Stage 2 · 设置模块**：先把 `Md3ListItemView` / `Md3SectionHeader` 建出来，再重排设置首页与 6 个二级页；每完成一个页面跑一次 `assembleDebug`。
