# EPUB New Core Architecture

## Current Position

The `epub-new-core` branch now uses a separate EPUB reader path. EPUB books are routed to `EpubReadView`; non-EPUB books still use the existing reader. During debugging, the new core should show an error page instead of silently falling back to the old EPUB core.

The first implemented architecture step is a fragment-based page model:

```text
EPUB XHTML -> ReaderModel -> PageFragment -> Canvas
```

This replaces the earlier long-term risk:

```text
EpubCorePage(text) -> StaticLayout -> Canvas
```

Canvas remains only the final paint layer. CSS, layout, pagination, hit testing, and anchors must live before Canvas.

## Implemented Foundation

- `EpubCorePage` now carries `fragments: List<EpubPageFragment>`.
- `EpubTextFragment` is used for normal text and preserves basic inline spans.
- `EpubImageFragment`, `EpubContainerFragment`, `EpubTableFragment`, `EpubFlexFragment`, and `EpubWebFragment` define stable extension points.
- `EpubCss`, `EpubStyleComputer`, and `EpubComputedStyle` collect CSS rules, compute inherited style, and sanitize unsupported browser features.
- `HtmlToReaderModel` now attaches computed style to blocks and inline nodes.
- `EpubLayoutBoxBuilder` converts styled reader blocks into layout boxes before pagination.
- `EpubCorePaginator` consumes layout boxes and outputs page fragments with page-local frames.
- `EpubPageRenderer` renders fragments recursively instead of drawing only `page.text`.
- `EpubLayoutBox` and `EpubComputedStyle` define the next layout and style boundary.

## Target Pipeline

```text
EPUB package
  -> archive/resource resolver
  -> XHTML DOM
  -> CSS collection and sanitization
  -> computed style
  -> layout tree
  -> layout boxes
  -> explicit page fragments
  -> Canvas renderer
```

The model must remain native-first. WebView is allowed only as a fragment fallback for unsupported blocks, not as the whole reader.

## Page Model

Pages use source ranges and fragments:

```kotlin
EpubCorePage(
    chapterIndex,
    chapterHref,
    pageIndex,
    totalPagesInChapter,
    fragments,
    start,
    end
)
```

Fragment types:

- `EpubTextFragment`: text, frame, line spacing, alignment, source.
- `EpubImageFragment`: internal image href, alt text, frame, source.
- `EpubContainerFragment`: background/border and child fragments.
- `EpubTableFragment`: table fragment container.
- `EpubFlexFragment`: limited flex fragment container.
- `EpubWebFragment`: isolated fallback for unsupported content.

## CSS Policy

The core should support a controlled CSS subset:

- font family, size, weight, style
- line height
- text color and background color
- text align and text indent
- margin, padding, border
- width, height, min/max width
- page-break before/after/inside
- display block/inline/list/table/flex subset

Unsupported features must degrade:

- `grid` -> block
- `position: fixed` -> ignored
- complex `absolute` -> static block
- `float` -> block
- animation, transition, transform, filter -> ignored
- script-dependent layout -> ignored

Reader user settings override book CSS for font, size, line height, text color, background, and spacing when configured.

## Next Implementation Order

1. Add real image decoding through the EPUB archive/resource resolver.
2. Strengthen block layout: margins, nested containers, list markers, and table/flex degradation.
3. Split container fragments across pages instead of keeping all decorated blocks together.
4. Add SourceAnchor-based hit testing, selection, highlight, and TTS mapping.
5. Add simple table layout and limited flex layout.
6. Add `EpubWebFragment` fallback only for blocks that cannot be represented natively.

## Test Requirements

- Plain text EPUB opens, paginates, and flips pages.
- Long paragraphs split by measured lines, not rough character counts.
- Basic bold/italic/underline spans render.
- Image blocks produce page fragments and do not exceed page width.
- Orientation, font size, line height, and margin changes invalidate pagination cache.
- New core failures stay visible during debugging and do not silently switch readers.
