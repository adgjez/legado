# Compose Migration Skill Guide

## 目标
将"我的"页面及其衍生页面/弹窗从传统 View/XML 迁移到 Jetpack Compose。

## 分支
`codex/compose-dialog-ui-20260608`

## 设计规范速查

### 设置页 (AppSettingPalette 驱动)
- 背景色: palette.page
- 卡片色: palette.row (经 surfaceColor 处理)
- 卡片圆角: panelRadius (10dp × uiCornerScale)
- 卡片边距: 12dp horizontal
- 分组间距: 10dp vertical
- 分区标题: 14sp, accent, Medium, 16dp padding
- 行标题: 16sp, primaryText
- 行副标题: 14sp, secondaryText, max 2 行
- 行最小高度: 60dp, 内边距 16dp H / 10dp V
- 分割线: 16dp 两侧内缩

### 弹窗 (AppDialogStyle 驱动)
- 宽度: 92%~98%, maxWidth 560~760dp
- 框架: AppDialogFrame → LegadoMiuixCard, 阴影 14dp
- 标题: 20sp, SemiBold, titleFontFamily
- 消息: 14sp, secondaryText
- 内容: LazyColumn, maxHeight 520dp, 18dp horizontal padding
- 操作按钮: 右对齐, LegadoMiuixActionButton
- 弹出动画: 160ms tween, scale 0.96→1.0

### 可复用组件
- 基础: LegadoMiuixCard, LegadoMiuixSwitch, LegadoMiuixSlider, AppThemedStepperSlider, LegadoMiuixActionButton, LegadoMiuixSection, LegadoMiuixActionRow, LegadoMiuixChoiceRow, LegadoMiuixSelectField
- 弹窗: AppDialogFrame, AppDialogSliderRow/Grid, AppDialogSwitchRow, AppDialogOptionGroup
- Modifier: appSettingPanelBackground, appSettingRowDecoration
- 快捷函数: showComposeConfirmDialog, showComposeTextInputDialog, showComposeTextFormDialog, showComposeNumberPickerDialog, showComposeMultiChoiceDialog, showComposeSingleChoiceDialog, showComposeActionListDialog, showComposeChoiceListDialog
- 设置页框架: ComposeSettingFragment, SettingSpecScreen, SettingPageSpec/SettingSectionSpec/SettingItemSpec

## 迁移策略
1. 复用已有组件 > 抽象新组件 > 逐页替换
2. 管理类 Activity → LazyColumn + 分组卡片模式
3. legacy 弹窗 → showCompose*Dialog 或 ComposeDialogFragment
4. XML 外壳 → Compose TopAppBar

## 迁移阶段
- Phase 0: 基础设施补全（ComposeManageScreen 框架、ComposeEditDialog 基类、ComposeImportDialog 基类）
- Phase 1: 高收益低风险（AiConfigFragment 弹窗、通用弹窗、子弹窗补全）
- Phase 2: MyFragment + ConfigActivity 外壳去 XML
- Phase 3: 管理类 Activity 批量迁移（从简单到复杂）
- Phase 4: 收尾（AboutActivity 外壳、ReadRecordActivity、ReadStyleDialog）

## 每步要求
1. 迁移一个完整组件
2. 验证无编译错误
3. 提交代码
4. 可选：打包 debug APK 验证

---

## 附：小说转视频（Novel-to-Video）Compose UI

小说转视频功能的 Compose 屏幕遵循上述同一套设计规范（`LegadoMiuixCard` / `AppDialogFrame` / `palette` 配色 / `panelRadius` 圆角等），位于 `app/src/main/java/io/legado/app/ui/novelvideo/`：

- `NovelVideoTaskCenterScreen` — 任务中心（`PrimaryTabRow` + LazyColumn 分组卡片）
- `NovelVideoJobDetailScreen` / `NovelVideoJobConfigSheet` — 任务详情 + 参数配置 BottomSheet
- `NovelVideoScreenplayReviewScreen` — 剧本审阅（可编辑/重新生成/确认）
- `NovelVideoComponents` — 共享 Compose 组件

视频 Provider 管理 UI（`ui/config/AiVideoProviderManageActivity` + `AiVideoProviderManageScreen` + `AiVideoProviderEditScreen`）仿 `AiImageProviderManageActivity` 形态。完整设计规格见 `docs/superpowers/specs/2026-07-05-novel-to-video-in-legado-design.md`。
