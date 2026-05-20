# EPUB Native Translation Layer Plan

## Goal

Build a rollback-friendly EPUB translation layer on top of `epub-new-core`.
The target pipeline is:

```text
HTML/CSS -> computed DOM -> native blocks -> StaticLayout/page line ranges -> Canvas
```

Normal text should behave like native Android text for layout, hit testing, and selection.
WebView remains a collector/fallback path for unsupported complex blocks, not the main renderer.

## Commit Rules

- Every implementation step must be committed separately.
- Each commit should compile where practical.
- Local APKs, debug folders, logs, and temporary files must not be committed.
- Use `cmd /d /c`, `rg`, or UTF-8 aware tools for reading Chinese paths/text.

## Step 1: Baseline And Hygiene

Commit message:

```text
chore(epub): prepare native translation baseline
```

Work:

- Create the implementation branch from `epub-new-core`.
- Ignore local build/debug artifacts.
- Save this task plan into `docs/EPUB_NATIVE_TRANSLATION_PLAN.md`.

## Step 2: Translation Model

Commit message:

```text
feat(epub): add native translation model
```

Work:

- Add `epubcore.translate` model types:
  - `EpubNativeDocument`
  - `EpubNativeBlock`
  - `EpubNativeTextBlock`
  - `EpubNativeImageBlock`
  - `EpubNativeFallbackBlock`
  - `EpubNativeInline`
  - `EpubNativeStyle`
  - `EpubSourceAnchor`
  - `EpubTranslationResult`
  - `EpubTranslationDiagnostics`
- Preserve DOM order with numeric `nodeOrder`; do not sort by raw `nodePath`.
- Preserve chapter, href, node path, text offsets, and fallback reasons.

## Step 3: Computed DOM Collector

Commit message:

```text
feat(epub): collect computed DOM styles for translation
```

Work:

- Extend the WebView measurement path or add a dedicated collector session.
- Collect DOM tree, text, computed style, box style, and resource references.
- Do not use browser line rectangles as the ordinary text layout source.
- Keep existing CSS parser as a fallback when WebView collection fails.

## Step 4: Block Classifier

Commit message:

```text
feat(epub): classify DOM blocks for native layout
```

Work:

- Classify each block as native text, native image, or fallback.
- Native v1 supports paragraphs, headings, quotes, simple divs, list items, simple inline spans, simple background/border/padding/margin.
- Fallback covers tables, flex/grid, float, absolute/fixed, transforms, vertical writing, complex ruby, and complex overflow.
- Emit diagnostics for native/fallback ratio and fallback reasons.

## Step 5: StaticLayout Builder

Commit message:

```text
feat(epub): layout translated text blocks with StaticLayout
```

Work:

- Build one `StaticLayout` per native text block.
- Apply Android spans for weight, style, color, underline, relative size, letter spacing, and line spacing.
- Do not convert browser baseline values into Paint baselines.
- Store line count, measured height, and source offset mapping.

## Step 6: Native Paginator

Commit message:

```text
feat(epub): paginate native blocks by StaticLayout lines
```

Work:

- Paginate by block and `StaticLayout` line ranges.
- Preserve split block decoration across pages without stretching backgrounds over the whole page.
- Keep image and fallback blocks as independent pagination units.

## Step 7: Renderer Integration

Commit message:

```text
feat(epub): render translated native pages
```

Work:

- Draw native text from `StaticLayout` line ranges.
- Draw block backgrounds, borders, radius, and images from native block boxes.
- Keep fallback rendering isolated.
- Centralize background cover/crop logic so re-entry cannot reuse stale white-border results.

## Step 8: Native Selection And Hit Testing

Commit message:

```text
feat(epub): support native text selection geometry
```

Work:

- Replace linear width-ratio selection with `StaticLayout` APIs:
  - `getLineForVertical`
  - `getOffsetForHorizontal`
  - `getSelectionPath`
- Fix DOM ordering by using numeric node order.
- Keep source anchors for copy, notes, highlights, and TTS.
- Fallback blocks may be non-selectable or block-selectable in v1.

## Step 9: Reader Feature Anchors

Commit message:

```text
feat(epub): map native anchors to reader features
```

Work:

- Save progress by chapter and source offset, not volatile page index alone.
- Route EPUB read-aloud/TTS through native source anchors.
- Keep top title, current chapter, and visible page anchor consistent after rapid switching.

## Step 10: Cache Versioning

Commit message:

```text
feat(epub): version native translation cache
```

Work:

- Add a native translation cache key containing book identity, file modified time, chapter href, translator version, layout config, page size, font, spacing, margin, theme, and page mode.
- Separate native translation cache from old Web layout cache.
- Invalidate only affected chapters when style/page config changes.

## Step 11: Scheduling

Commit message:

```text
feat(epub): schedule translated pages ahead of reading
```

Work:

- Preprocess current chapter first, then nearby chapters.
- Use scheduling modes:
  - light: current plus previous/next 1 chapter
  - normal: current plus previous/next 2 chapters
  - performance: current plus previous/next 3 to 5 chapters
- Split background work into DOM/CSS translation and StaticLayout pagination stages.
- At chapter boundaries, switch chapter state first and show a stable loading page if needed.

## Step 12: Fallback Isolation

Commit message:

```text
feat(epub): isolate web fallback blocks
```

Work:

- WebView fallback should render only unsupported blocks, not the whole chapter.
- Fallback failures show a local error block instead of blanking the whole chapter.
- Record fallback count, reason, and render timing.

## Step 13: Tests

Commit message:

```text
test(epub): cover native translation ordering and selection
```

Work:

- Test numeric DOM order, especially `/p/2` before `/p/10`.
- Test paragraph split pagination and cross-page source ranges.
- Test selection geometry alignment from `StaticLayout`.
- Test fallback block isolation.
- Manually verify the known EPUB samples:
  - `测试文件 简单 - 可能存在页码问题(3).epub`
  - `道诡异仙 - 狐尾的笔.epub`
  - `《超神机械师》作者：齐佩甲 - 齐佩甲.epub`
  - `诡秘之主 - 爱潜水的乌贼.epub`
  - `大奉打更人 - 卖报小郎君.epub`

## Step 14: Architecture Docs

Commit message:

```text
docs(epub): document native translation pipeline
```

Work:

- Update `docs/EPUB_NEW_CORE_ARCHITECTURE.md`.
- Document the new flow, fallback policy, cache versioning, scheduling, and known unsupported CSS.

## Acceptance Criteria

- Ordinary EPUB body text can be selected with native-level hit testing.
- Selection handles can be dragged without large offset drift.
- Fast chapter switching does not jump to the wrong chapter.
- Page-turn chapter boundaries do not require a fully rendered next chapter before state changes.
- Background cover/crop is stable across first entry, re-entry, reload, and rotation.
- Complex CSS fallback does not blank or stall the entire chapter.
