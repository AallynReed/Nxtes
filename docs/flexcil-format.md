# Flexcil backup format

As written by Flexcil for Android 1.5.0.13. The importer in `android/app/src/main/kotlin/net/mildhope/nxtes/flexcil/` follows this.

These are facts about the file format only, for interoperability, recovered statically from `com.flexcil.flexcilnote` 1.5.0.13 (base APK, SHA-256 `7f566678…e5bb`). Nothing was
executed. Class names are the obfuscated JADX names, cited as evidence. They are not stable across
Flexcil versions.

**Method.** Static reading of the shipped app with REA's JADX provider, plus `aapt2` for the folder colour values. Nothing was executed, and no Flexcil code is reproduced here.

**Confidence tags:** **[read]** means both the writer and the reader were followed. **[inferred]** means it
follows from the code but not every path was traced. **[open]** means a real backup is needed to settle it.

---

## 1. Archive layout (`.flex`)

### 1.1 Container

- **Format:** a standard ZIP written with `java.util.zip.ZipOutputStream`. Each entry uses method
  DEFLATE at **compression level 0**, so the data is effectively stored. There's no encryption or
  password, and nothing is wrapped around the ZIP. **[read]** `nf.a.h` (`ArchiveZipUnzipHelper`),
  called from `MainActivity` coroutine `w` (`onBackupFiles`).
- **Entry names:** UTF-8, the Java default, which sets the language-encoding flag. Entry mtimes are
  the source files' `lastModified`. Empty directories appear as entries ending in `/`.
- **Validation:** Flexcil runs a zip4j validity check after writing (`sy.a.H()`). On restore it falls
  back to zip4j if `java.util.zip` fails (`nf.b0.c`).
- **File name:** `Flexcil Backup yyyy-MM-dd HH.mm.ss.flex` in local time, or `<folder name>.flex` when
  exporting a single folder (`FolderExportPopupLayout`). The MIME type registered for VIEW/SEND is
  `application/flex`.

### 1.2 Entry tree

Every entry sits under one top-level directory named **`flexcilbackup/`**. The zip root is the
*parent* of the staging folder `<cache>/backup/flexcilbackup`. On restore, Flexcil uses the extracted
directory if it contains `info` directly, or else the first child directory that contains `info`
(`MainActivity.b0`). An importer should accept both layouts.

```
flexcilbackup/
  info                                   backup manifest, plain JSON (§2.1)
  Documents/                             required
    <DocName>.flx                        document at library root (§3)
    <FolderName>/                        one directory per folder level
      .itemInfo                          folder metadata, compressed JSON (§2.2)
      <DocName>.flx
      <SubFolder>/
        .itemInfo
        <DocName>.flx
  Recordings/                            only if any exported document has audio
    <AUDIOKEY>.fab                       nested ZIP per recording (§4.18)
  .Reference/                            directory is always created
    <REFKEY>.references                  compressed JSON (§4.19)
```

**Evidence [read]:** `MainActivity` coroutine `w`. It writes `info` from `df.c`, exports each selected
document with `pg.h.d(...)` into `Documents/`, copies recordings with `ej.a.a`, writes
`.Reference/*.references`, and then zips with `nf.a.h`. The restore side is `of.p1` coroutine `c` and
`pg.h.z0`, which walks the folders.

**Folder hierarchy.** `pg.h.d` walks the document's ancestor folders from the root down and creates
one directory per ancestor.

- **Directory names:** the folder's display name, sanitised by `pg.h.K0`. It truncates to 82 chars
  and replaces `/ NUL \ | : * ? < > "` with `_`. Use `.itemInfo` `name` for the real name.
- **`.itemInfo`:** written into each ancestor directory from that folder's file item (`gf.c.W`).
- **Root-level documents** go directly in `Documents/`. The unnamed library root isn't a directory.
- **Empty folders and unselected documents** are not exported. Only the documents passed to the
  backup appear, plus their ancestor folders.
- **`.flx` file name:** `<document display name>.flx`. If that name is taken in the target directory,
  Flexcil uses a de-duplicated sanitised name with ` (n)` (`pg.h.X`). The authoritative name is
  `name` in the document's `info`.

**Not in a backup:**

- the library index (`gf.d`, `JFlexcilDocumentListAdapter`) and so any explicit sort order
- trash (`JFlexcilDocumentTrashListAdapter`)
- sticker packs (`StickerPacksV2`, `jf.k`/`jf.m`)
- app settings, user fonts, the template catalogue, sync state

Template PDFs *are* effectively included, because every page's background PDF is copied into its
document (§3). **[read]**

**Ordering:** nothing in the archive records an order of folders or documents. Directory listing
order is meaningless. **[read]**

### 1.3 Compressed-JSON envelope (`.itemInfo`, `.references`)

Several small metadata files use the same envelope, and the reader accepts it the same way each time.
**[read]** `gf.c.W` and `MainActivity` write it; `pg.h.z0` and `document.c.a.a` read it.

| Offset | Size | Content |
|---|---|---|
| 0 | 8 | uncompressed byte length, unsigned 64-bit **little-endian** |
| 8 | n | **raw DEFLATE** stream (no zlib header or checksum, i.e. `nowrap`), default level |

The payload is UTF-8 JSON. Quirk: when reading folder `.itemInfo`, Flexcil URL-decodes the inflated
text before parsing it (`URLDecoder.decode`), which suggests some writers (iOS?) percent-encode it.
Android doesn't encode it. A safe importer parses the text as JSON first and URL-decodes only if that
fails. Note that decoding plain JSON this way would turn `+` into spaces.

---

## 2. Backup-level files

### 2.1 `flexcilbackup/info` (backup manifest)

Plain JSON written by `new Gson()`. Model `df.c` (`BackupRootInfoFile`). **[read]**

| JSON name | Type | Meaning | Evidence |
|---|---|---|---|
| `appName` | string | `"FlexcilNote_Android"` | `df.c` |
| `backupDate` | string | local time, `yyyy-MM-dd HH.mm.ss` | `df.c` |
| `appVersion` | string | writer version, e.g. `"1.5.0.13"` | `df.c` (SerializedName is a Firebase constant = `appVersion`) |
| `version` | string | backup format version, `"4.0.0"` | `df.c` |

On restore Flexcil requires major version ≥ 4 and needs both `info` and `Documents` present
(`of.p1`). A missing or unreadable `info` is treated as `"1.0.0"` and rejected.

### 2.2 `.itemInfo` in a folder directory

Compressed envelope (§1.3) around JSON for `df.a` (`BackupDirectoryItemInfo`), written by plain Gson,
so null fields are omitted. **[read]** `gf.c.W` writes it; `pg.h.z0` reads it.

| JSON name | Type | Meaning | Evidence |
|---|---|---|---|
| `originKey` | string | the folder's original file-item key (UUID) | `df.a` |
| `name` | string? | folder display name; falls back to the directory name | `df.a`, `pg.h.z0` |
| `color` | string? | folder colour **name** (see §5.10) | `df.a`, `ql.j2.Z` |
| `createDate` | number | seconds since Unix epoch (double) | `df.a`, `nf.s.a` |
| `modifiedDate` | number | seconds since Unix epoch (double) | `df.a` |
| `favorite` | bool | starred | `df.a` |

### 2.3 `.itemInfo` at the root of a `.flx`

Same envelope, JSON for `df.b` (`BackupDocumentItemInfo`). **[read]** written by `gf.c.W`.

| JSON name | Type | Meaning |
|---|---|---|
| `originKey` | string | the document's file-item key (UUID; not necessarily the document key) |
| `favorite` | bool | starred |

---

## 3. Document package (`.flx`)

A `.flx` is itself a ZIP: `ZipOutputStream`, DEFLATE level 0, no password. Entries are relative to
the document's own directory, so there's no top-level folder. **[read]** `pg.h.d` → `nf.a.g(dir, out,
false)`. Before zipping, Flexcil deletes an empty `outlines` file, writes `.itemInfo`, and copies
`thumbnail` to `thumbnail@2x`/`thumbnail@3x`.

On the device the folder is `Flexcil/Documents/<DOCUMENT_KEY>/` (`document.a.t`). Inside the `.flx`
the layout is:

| Path in `.flx` | Format | Content | Evidence |
|---|---|---|---|
| `info` | JSON object | document metadata (§4.1) | `document.a` (`JDocument`) |
| `pages.index` | JSON array | ordered page list (§4.3) | `document.b` (`JDocumentPage`) |
| `template.info` | JSON array | template ↔ attachment map (§4.4) | `TemplateInfoTypeAdapterForSerialize` |
| `attachment/PDF/<ATTACHMENT_KEY>` | PDF, no extension | page backgrounds: the imported PDF and every template PDF used | `document.a.n`, `pg.h.l` |
| `attachment/image/<IMAGE_OBJECT_KEY>` | PNG (if alpha) or JPEG, no extension | pixels for image objects | `tm.m`, `pg.h.a` |
| `objects/<PAGE_KEY>.objects` | JSON array | per-page z-order list (§4.5) | `qm.c.a.g/q` |
| `objects/<PAGE_KEY>.drawings` | JSON array | ink strokes (§4.6) | `JDrawingTypeAdapterForSerialize` |
| `objects/<PAGE_KEY>.shapes` | JSON array | shape objects (§4.7) | `JShapeTypeAdapterForSerialize` |
| `objects/<PAGE_KEY>.annotations` | JSON array | text highlight/underline (§4.8) | `JDrawingAnnotationTypeAdapter` |
| `objects/<PAGE_KEY>.maskings` | JSON array | masking-pen strokes (§4.9) | `JMaskingTypeAdapterForSerialize` |
| `objects/<PAGE_KEY>.maskingAnnotations` | JSON array | masking over PDF text (§4.10) | `JMaskingAnnotationTypeAdapter` |
| `objects/<PAGE_KEY>.images` | JSON array | image/sticker placements (§4.11) | `JImageFileTypeAdapterForSerialize` |
| `objects/<PAGE_KEY>.texts` | JSON array | text boxes (§4.12) | `JTextboxTypeAdapterForSerialize` |
| `objects/<PAGE_KEY>.stickynotes` | JSON array | sticky notes (§4.13) | `JStickynoteAnnoTypeAdapter` |
| `objects/<PAGE_KEY>.hyperlinks` | JSON array | link rectangles (§4.14) | `jf.d.a` |
| `outlines` | JSON array | PDF-style outline tree (§4.15) | `hf.b` |
| `bookmark` | JSON array | user bookmarks (§4.16) | `hf.a` |
| `audiorecord.refs` | JSON object | audio keys used by this document (§4.17) | `ff.a`, `JFlexcilAudioRecordingReferencesAdapter` |
| `thumbnail`, `thumbnail@2x`, `thumbnail@3x` | JPEG (quality 100), no extension | cover thumbnail; the @2x/@3x copies are identical | `pg.h` (`CompressFormat.JPEG`), `gf.c.W` |
| `.itemInfo` | compressed envelope | §2.3 | `gf.c.W` |
| `*_back` siblings | same as main file | crash-safety copies | many writers |

**Notes:**

- **Keys:** every key (document, page, object, attachment, audio, reference) is
  `UUID.randomUUID().toString().toUpperCase()`: 36 characters, uppercase hex with hyphens
  (`nf.m.a.j`). `qm.c.a.a` relies on the document key being 36 characters long. **[read]**
- **Missing files:** any per-page file may be absent, which means no objects of that kind.
  **[read]**
- **`_back` rule:** writers first write `<file>_back` and then `<file>`. Readers use `<file>` if it
  exists and is non-empty, otherwise `<file>_back` (`nf.m.a.Q`/`J`). Some readers also fall back to
  `_back` on a parse error, and some recover from files where two JSON arrays got concatenated
  (`][`), splitting them and de-duplicating by `key` (`qm.c.a.k`). The importer should do the same.
  **[read]**
- **Attachment keys:** for a freshly imported PDF the attachment key equals the document key
  (`pg.h`, PDF import). After any restore or copy, attachment keys are regenerated. Never assume
  they're equal; resolve through `pages.index` → `attachmentPage.file`. **[read]**
- **Image file names:** image files are named after the image object's `key`. Restore renames the
  file together with the key (`pg.h.a`). **[read]**

---

## 4. JSON schemas

Conventions that apply to every file:

- **Writers.** Two kinds of writer exist. Gson with `excludeFieldsWithoutExposeAnnotation` (or plain
  Gson) uses the `@SerializedName` JSON names. Custom `TypeAdapter`s write fields by hand. For the
  object files the adapters are authoritative. Where an adapter's name differs from the model's
  `@SerializedName`, I give the **adapter's** name (the one on disk).
- **Nulls** are omitted by default (no `serializeNulls` on any writer here).
- **Escaping.** Gson HTML-escapes `< > & = '` as `<` and so on. Use a real JSON parser.
- **Numbers.** Doubles are written with Java `Double.toString`, so large values such as epoch
  seconds can appear in exponent form (`1.729E9`). Floats are written as JSON numbers.
- **Colours** in object files are ARGB packed into 32 bits and written as an **unsigned** integer
  (0…4294967295). Read them as int64 and mask with `0xFFFFFFFF`. Older files may hold negative
  signed values; masking handles both. **[read]**
- **Dates** are seconds since the Unix epoch as a double (`nf.s.a`), except masking `createDate`,
  which is **milliseconds** (§4.9).
- **Base fields.** Most models extend `ef.a` (`JItem`), which carries `key` (string), `additional`
  (string, optional; free-form, e.g. a JSON string `{"adId":…}` on pages) and `version` (string,
  default `"0.0.5"`). Object models extend `jf.g` (`JObject`), which adds `type` (int, §5.1). The
  `parent` field of `ef.b` is a runtime back-reference and never appears in practice.

### 4.1 `info` (document)

Written with Expose-only Gson (`document.a.I`), read with plain Gson. **[read]**

| JSON name | Type | Meaning / units | Evidence |
|---|---|---|---|
| `key` | string | document key (UUID); also the device folder name | `ef.a` |
| `version` | string | model version, `"0.0.5"` at write time; ≤ `0.0.3` triggers a migration that sets `useCover` for notes | `document.a.C0224a.a` |
| `additional` | string? | free-form | `ef.a` |
| `name` | string | document display name | `document.a` |
| `type` | int | document kind, §5.2 | `document.a`, `cf.j` |
| `useCover` | bool | the first page is a cover (default true for notes) | `document.a` |
| `createDate` | number | epoch seconds | `document.a` |
| `modifiedDate` | number | epoch seconds | `document.a` |
| `currentPage` | string? | key of the last-viewed page | `document.a` |
| `attachments` | object<string,string> | attachment key → **PDF password** (`""` when there's none); one entry per file in `attachment/PDF` | `document.a.l`, `pg.h.M0` |
| `references` | string[]? | keys of scrap references (§4.19) involving this document | `document.a.m` |
| `copyright` | string? | marketplace content-owner id. If set, the `attachments` values are AES-GCM encrypted with an owner key that isn't in the file (`nf.k`, `cf.h`) | `document.a.M` |

### 4.2 Folder and document `.itemInfo`

See §2.2 and §2.3.

### 4.3 `pages.index`

A JSON array of page objects in **document order**, written with plain Gson (`document.b.a.f`).
**[read]**

| JSON name | Type | Meaning / units | Evidence |
|---|---|---|---|
| `key` | string | page key (UUID); names the `objects/<key>.*` files | `ef.a` |
| `version` | string | `"0.0.5"`; also changes how `annotationSubtypes` is read (below) | `ef.a`, `rf.j.b` |
| `additional` | string? | free-form; ad documents put `{"adId":"<docKey>"}` here | `pg.h` (PDF import) |
| `frame` | object `{x,y,width,height}` (floats) | page rectangle **in PDF points of the unrotated source page**. Imported PDF pages use `{0,0,pdfW,pdfH}`. Template pages use the template PDF's first-page size (default 768×1024 if unknown). x and y have always been 0 where I saw pages created. | `kf.h`, `pg.h` |
| `rotate` | float | page rotation in **degrees**, used modulo 360; multiples of 90 | `document.b.q/t` |
| `attachmentPage` | object? | background source: `{"file": <attachment key>, "index": <0-based page index in that PDF>}`. **Absent** means a blank page with no PDF background, created by "add blank page" from a neighbour's size. | `kf.b`, `pg.h` |
| `annotationSubtypes` | int[]? | PDF annotation subtypes (values in §5.9) that Flexcil has *taken over* from the source PDF: they're hidden when the background is drawn and stripped on export. An empty or missing list is treated as `[2]` (LINK). For page `version` ≤ `0.0.4` the list was inverted (it listed what to keep). | `document.b.a.b`, `rf.j.b`, `qg.r.a` |

**Blank notes.** There are no procedural backgrounds. Notes are PDF pages whose `attachmentPage`
points at a copy of the template PDF in `attachment/PDF`. **[read]** `pg.h.l`.

### 4.4 `template.info`

A JSON array written by `TemplateInfoTypeAdapterForSerialize`. **[read]**

| JSON name | Type | Meaning |
|---|---|---|
| `attachmentKey` | string | key of the template PDF in `attachment/PDF` |
| `key` | string | Flexcil template identifier (`TemplateItem.getKey()`) |
| `version` | string | item version |

An importer can ignore this file, since the background PDF is already referenced from
`pages.index`. It's only useful for recognising which built-in template a page used.

### 4.5 `objects/<PAGE_KEY>.objects` (z-order list)

A JSON array of `{"type": int, "key": string}` (`jf.h`), written with Expose-only Gson. **[read]**

- **Draw order:** rendering, export and erasing all iterate this list (`qm.c`, `qg.r`), so it's the
  authoritative order. Later entries are presumably drawn on top. **[inferred]**
- **Orphans:** objects in a kind file but missing from this list are only re-added after
  corrupted-file recovery (`qm.c.n`). Otherwise Flexcil doesn't draw them. Importer suggestion:
  follow this list, then append orphans and warn.
- **Hyperlinks** (type 40) are not listed. They live only in `.hyperlinks`.

### 4.6 `objects/<PAGE_KEY>.drawings` (ink strokes)

A JSON array; each element is written by `JDrawingTypeAdapterForSerialize`. Model `jf.a`
(`JDrawing`). **[read]**

| JSON name | Type | Meaning / units | Evidence |
|---|---|---|---|
| `key` | string | object key | `ef.a` |
| `type` | int | `1` drawingBallPen (normal ink) or `2` drawingHighlighter, §5.1 | `jf.g` |
| `mode` | int | pen/brush, §5.3. A value of `10` is never written; it's rewritten to `1`. | adapter `write` |
| `figure` | int | `0` freehand, or a recognised shape, §5.5 | `jf.a` |
| `dashtype` | int | §5.6: `0` solid, `1` dash, `2` dot | `jf.a` |
| `strokeColor` | uint32 | ARGB; alpha is the stroke opacity | adapter |
| `fillColor` | uint32 | ARGB fill for closed figures (0 = none) | adapter |
| `points` | string | **base64 point blob**, see below | `jf.c.a/b` |
| `start` | `{x,y}` floats | origin added to every decoded point | adapter |
| `scale` | `{x,y}` floats | multiplier applied to decoded x/y before adding `start`. Android always writes `{1,1}`. | adapter, `jf.a` constructor |
| `rotate` | double | object rotation in **radians** (see below) | `jf.a.j0/h0`, `nf.c.I` |
| `seed` | int? | texture random seed (texture fountain pen); written only when set | `xm.u` |
| `phase` | float? | texture grain phase 0..1 (texture pencil); written only when set | `xm.v` |

**Point blob encoding [read]** (`jf.c.a` writes it, `jf.c.b` reads it):

1. Base64-decode with the standard alphabet and padding (Java `Base64.getEncoder()`).
2. Bytes 0–3 hold the point count as int32 LE. The reader ignores it and iterates by length.
3. Then come N records of 12 bytes each: **x, y, w as float32 LE**.
   - x and y are stored *relative to `start`*. The writer subtracts the minimum x and minimum y over
     all points, and that minimum is what it writes as `start`.
   - **w** is the stroke width at that point. It's absolute, not offset or scaled.
4. Absolute position: `X = x·scale.x + start.x` and `Y = y·scale.y + start.y`, in page units (§6).

**Width semantics.** Pressure is baked into `w`; there's no separate pressure value.

- Ballpen, highlighter and similar pens: `jf.a.E` uses the first point's `w` as the stroke width.
- Fountain, texture fountain and texture pencil: `jf.a.F/K/Q` use per-point widths (the maximum is
  the nominal width).

**There is no tilt, no per-point timestamp and no pressure.** **[read]**

**Rotate semantics:**

- For ordinary pens the points are already in final page position. Rotating a stroke rewrites its
  points (`jf.a.h0` → `l0`) and also accumulates `rotate`.
- `rotate` matters for rendering only for the orientation-sensitive pens (`mode` 20
  square highlighter, `mode` 21 slanted fountain; `jf.a.W`). For these Flexcil applies it about the
  bounding-box centre. **[inferred]**

**Figure geometry** (`jf.a.N`), used when `figure` ≠ 0:

- `line`: first point to last point.
- `rectangle`: the axis-aligned rectangle spanned by the first and last points.
- `ellipse`: an ellipse inscribed in that rectangle.
- `rhombus`, `triangle`, `polygon`: a closed polygon through all the points.
- `polyline`: an open polyline through the points.
- Freehand: a straight line if there are exactly 2 points, otherwise a polyline.

Brush rendering of freehand ink is done by per-pen drawers (`xm.*`) whose look Nxtes has to
approximate. **[read]**

### 4.7 `objects/<PAGE_KEY>.shapes`

Written by `JShapeTypeAdapterForSerialize`. Model `jf.i` (`JShape`). `type` is always `32`. **[read]**

| JSON name | Type | Meaning / units |
|---|---|---|
| `key`, `type` | | base fields (type 32) |
| `shapeType` | int | §5.7 |
| `points` | string | point blob, same encoding as §4.6 (x, y, w relative to `start`). For polygons and polylines these are the vertices. For curves they're start, end and optionally more. |
| `start` | `{x,y}` | origin for `points` |
| `scale` | `{x,y}` | multiplier for `points`, always `{1,1}` on Android |
| `controlPoints` | `[{x,y},…]` | **absolute** page-unit control points (not start-relative). For `curve` they're the quadratic control point(s) between the first and last points; the default is the midpoint (`jf.i.D`). Usually empty for other types. |
| `sides` | int? | polygon side count. Derived for the other types: rectangle 4, triangle 3, line 1, polyline = n−1, freeform = n; null for ellipse, curve, arrow, heart. |
| `rotate` | double | **radians**. For shapes the points are *unrotated*: the renderer rotates about the bounding-box centre of points plus control points (`xm.l`, `canvas.rotate`). |
| `dashtype` | int | §5.6 |
| `strokeColor`, `fillColor` | uint32 | ARGB. Fill is forced to 0 for line, arrow, curve, polyline and polylinecurve. |

Stroke width is the first point's `w` (`jf.i.H`). For rectangle, ellipse, triangle and polygon the
exact vertex convention is **[open]** (§9).

### 4.8 `objects/<PAGE_KEY>.annotations` (PDF text highlight/underline)

Written by `JDrawingAnnotationTypeAdapter`. Model `jf.b` (`JDrawingAnnotation`). **[read]**

| JSON name | Type | Meaning / units |
|---|---|---|
| `key`, `type` | | type `10` = underline-style (annotationBallPen), `11` = highlight (annotationHighlighter) |
| `mode` | int | pen mode: `1` (ballPen) for type 10, `2` (highlighter) for type 11 |
| `strokeColor` | uint32 | ARGB |
| `text` | string | the selected PDF text |
| `paths` | array of `{points, width}` | one entry per text line. `points` is a base64 blob of **int32 LE count + N × (x, y) float32 LE** (8 bytes per point, *absolute* page units, no `start`). There are always 2 points: a horizontal segment. `width` (double) is the band thickness in page units. Highlight: the segment runs through the line's vertical centre and `width` = line height. Underline: the segment sits at the line bottom and `width` = pen thickness. (`nf.v.a/c`, `nf.c.J`) |
| `frame` | rect | union of the line rectangles (page units) |
| `markerFrame` | rect | selection rectangle (page units) |

`dashtype` exists in the model but the adapter doesn't write it.

### 4.9 `objects/<PAGE_KEY>.maskings` (masking-pen strokes)

Written by `JMaskingTypeAdapterForSerialize`. Model `jf.e` (`JMasking`). `type` is `50`. **[read]**

| JSON name | Type | Meaning |
|---|---|---|
| `key`, `type` | | type 50 |
| `mode` | int | pen mode, normally `7` (masking) |
| `figure` | int | §5.5 |
| `patternType` | int | §5.8: `0` check, `1` stripe, `2` dot |
| `points`, `start`, `scale` | | same encoding as §4.6 |
| `strokeColor`, `fillColor` | uint32 | ARGB |
| `rotate` | double | radians |
| `createDate` | int64 | **epoch milliseconds**, and set to *the time of the last save*, not creation |
| `correct`, `wrong` | int | study-quiz counters |

### 4.10 `objects/<PAGE_KEY>.maskingAnnotations` (masking over PDF text)

Written by `JMaskingAnnotationTypeAdapter`. Model `jf.f`. `type` is `51`. **[read]**

The fields are `key`, `type`, `mode`, `patternType`, `strokeColor`, `fillColor`, `markerFrame`,
`frame`, `paths` (same encoding as §4.8), `text`, `createDate` (epoch ms, save time), `correct` and
`wrong`.

Pitfall: the model's `@SerializedName` says `path`, but the adapter writes and reads **`paths`**.

### 4.11 `objects/<PAGE_KEY>.images`

Written by `JImageFileTypeAdapterForSerialize`. Model `objects.page.a`. `type` is `20`. **[read]**

| JSON name | Type | Meaning |
|---|---|---|
| `key` | string | object key; also the file name in `attachment/image/` |
| `type` | int | 20 |
| `frame` | rect | **visible (cropped) placement rectangle** on the page, in page units. If `freeCrops` is non-empty, it's the bounding box of the free-crop polygon. |
| `cropBox` | rect | crop rectangle in **image-normalised** coordinates (0..1 of the uncropped image); `{0,0,1,1}` means uncropped |
| `freeCrops` | `[{x,y},…]` | optional lasso-crop polygon in image-normalised coordinates; points with x or y = −9 are separators or invalid and are skipped |
| `rotate` | double | radians, applied about the frame centre at render time |
| `copyright` | string? | marketplace content owner (stickers, store images) |

**Reconstructing the full image rectangle.** Without free crops, the full image rectangle is `frame`
scaled by 1/cropBox size and shifted by −cropBox origin (`a.C0226a.c`). With free crops, Flexcil does
the same thing using the polygon's bounding box. **[read]**

Placed stickers end up here too, as image objects. Sticker-pack metadata isn't stored per document.
**[inferred]**

### 4.12 `objects/<PAGE_KEY>.texts` (text boxes)

Written by `JTextboxTypeAdapterForSerialize`. Model `jf.p`. `type` is `30`. **[read]**

| JSON name | Type | Meaning |
|---|---|---|
| `key`, `type` | | type 30 |
| `frame` | rect | box in page units |
| `rotate` | double | radians |
| `text` | string | plain text of the whole box |
| `columns` | array | rich text: one element per paragraph, `{"p": {"span": [...], "reflow": [int...], "offset": int}}` |

Inside `columns`:

- `span[]` is a list of `{"text": string, "style": {...}}`.
- `style` holds:
  - `font-size`: double, **page-width units**. The default 0.0299479 is 23/768 (`kf.j`).
  - `font-family`: string, default `"Arial, Arial"`.
  - `font-weight`: string?, `"bold"`.
  - `font-style`: string?, space-separated tokens from `italic`, `underline`, possibly `bold`.
  - `color`: `"#RRGGBB"`.
- `reflow`: cumulative word counts at soft line breaks (`kf.c.a`). It's a layout cache, so an
  importer can ignore it.
- `offset`: 1-based paragraph index.

Pitfall: the models' `@SerializedName` values are `paragraph`/`spans`/`fontSize`/`fontFamily`…, but
the adapters write **`p`, `span`, `font-size`, `font-family`, `font-weight`, `font-style`, `color`**.
The model also has an `annotations` list (`kf.a`) that the adapter never writes.

### 4.13 `objects/<PAGE_KEY>.stickynotes`

Written by `JStickynoteAnnoTypeAdapter`. Model `jf.o`. `type` is `60`. **[read]**

The fields are `key`, `type`, `frame` (rect, page units), `text` (string), `color` (int?) and
`icon` (int?). Whether `color` is a palette index or ARGB, and what the icon ids mean, is **[open]**.

### 4.14 `objects/<PAGE_KEY>.hyperlinks`

Expose-only Gson array of `jf.d`. `type` is `40`. **[read]**

| JSON name | Type | Meaning |
|---|---|---|
| `key`, `version`, `type` | | base fields (`version` usually `"0.0.5"`) |
| `frame` | rect | hot area, page units |
| `actionType` | int | §5.11 |
| `link` | string? | target page key (go-to links, e.g. planner navigation) or URL |

### 4.15 `outlines`

Expose-only Gson array of `hf.b`. It comes from the PDF outline on import and can be edited.
**[read]**

The fields are `key`, `version`, `name` (string), `link` (string, the target **page key**),
`actionType` (int, §5.12) and `children` (same structure, recursive). Flexcil deletes a zero-length
`outlines` file before export.

### 4.16 `bookmark`

Expose-only Gson array of `hf.a`. **[read]**

The fields are `key`, `version`, `name` (string), `type` (int: `0` page bookmark, `1` group),
`pageKey` (string?) and `children` (same structure, for groups).

### 4.17 `audiorecord.refs`

`{"refs": ["<AUDIOKEY>", …]}`, written by `JFlexcilAudioRecordingReferencesAdapter`. It lists the
recordings attached to this document, and the backup collects exactly these into `Recordings/`.
**[read]**

### 4.18 Audio recordings: `Recordings/<AUDIOKEY>.fab`

On the device each recording lives in `Flexcil/Recordings/<AUDIOKEY>/`. The backup zips that
directory (`nf.a.g`, entries relative to the directory) into `<AUDIOKEY>.fab`. On restore Flexcil
picks the entries by extension. **[read]** `ej.a.a/b`.

| Entry | Format | Content |
|---|---|---|
| `<AUDIOKEY>.flxa` | **MPEG-4 / AAC-LC**, 44.1 kHz, stereo, 128 kbit/s (a plain `.m4a` under another name) | the audio (`vi.c`: `MediaRecorder` output format MPEG_4, encoder AAC) |
| `<AUDIOKEY>.rinfo` | JSON | recording info (below) |
| `<AUDIOKEY>.rsync` | JSON, optional | stroke ↔ time sync (below) |
| `*_back` | | crash-safety copies |

**`.rinfo`** (`JFlexcilAudioRecordingSyncInfoAdapter`, model `aj.c`):

| JSON name | Type | Meaning |
|---|---|---|
| `key` | string | audio key |
| `name` | string | user title (a localised default is used if missing) |
| `startdoc` | string | document key where recording started |
| `start` | double | recording start, epoch seconds |
| `duration` | double | seconds |
| `includeDocuments` | `[{"dockey": string, "pages": [pageKey…]}]` | pages that received objects during this recording |

The reader rejects the file unless `startdoc`, `key`, `start` and `duration` are present and
`includeDocuments` is non-empty.

**`.rsync`** (`JFlexcilAudioRecordingSyncDataListAdapter`, model `aj.d`):
`{"items": [{"addtime": double, "dockey": string, "pagekey": string, "obj": string}, …]}`.

- `obj` is any page object's key (stroke, shape, text…).
- `addtime` is **absolute epoch seconds** when the object was added. The playback position is
  `max(0, addtime − rinfo.start)` seconds (`AnnotationPDFView`, `rm.c`).
- Objects added while *playing back* an existing recording get
  `addtime = start + playbackPosition − 0.21 s`.

### 4.19 `.Reference/<REFKEY>.references` (scraps)

Compressed envelope (§1.3) around JSON written by `JReferenceTypeAdapter` (model `document.c`).
**[read]**

It records a "scrap": a region clipped from one page and pasted into another as an image or text box.

```
{ "key": REFKEY,
  "destination": { "uri", "recordedPageIndex": int, "recordedDocumentName": string },
  "source":      { "recordedDocumentName", "markerFrame": rect, "recordedPageIndex": int,
                   "type": int (0 text, 1 image), "uri", "scrapFrame": rect,
                   "paths"?: [ {points,width} … ] (as §4.8), "text"?: string } }
```

- **`uri`:** `flexcilRS://flexcil/<docKey>/<pageKey>[/<objectKey>]` for sources and `flexcilRD://…`
  for destinations (`cf.n.g/h`, `ff.d.b/c/d`).
- **Destination:** the third path element is the key of the pasted image or text object.
- **Inclusion:** only references whose source and destination documents are both in the backup are
  included.

This is low priority for an importer. It only links pasted content back to where it came from.

---

## 5. Enums and constants

All are from `cf.*` (`GlobalEnum.kt`) unless noted. **[read]**

### 5.1 Object `type` (`cf.q`)

| Value | Kind | Value | Kind |
|---|---|---|---|
| 0 | pdfAnalysis | 32 | shape |
| 1 | drawingBallPen (ink) | 40 | hyperlink |
| 2 | drawingHighlighter (ink) | 50 | masking |
| 10 | annotationBallPen (text underline) | 51 | annotationMasking |
| 11 | annotationHighlighter (text highlight) | 60 | stickyNote |
| 20 | imageFile | 99 | none |
| 30 | textbox | | |

### 5.2 Document/file-item `type` (`cf.j`)

`0` PDF, `1` directory (library only), `4` note, `5` planner, `99` locked (password-protected) PDF.

### 5.3 Pen `mode` (`cf.k`)

| Value | Pen | Value | Pen |
|---|---|---|---|
| 1 | ballPen | 7 | masking |
| 2 | highlighter | 8 | shape |
| 3 | eraser (not stored on objects) | 20 | highlighter_squre (square-nib highlighter) |
| 4 | gesterPen | 21 | texture_fountain_slanted |
| 5 | fountain | 22 | texture_fountain_round |
| 6 | pencil | 23 | texture_pencil |

`10` is an internal value rewritten to `1` on save. The toolbar enum `cf.a` uses different numbers
(10/11/12 for the textures, 1021+ for tools) and is **not** what gets stored.

### 5.4 Ink object type vs pen

Highlighter ink has `type` 2 and `mode` 2 or 20. Everything else is `type` 1. The marketing
"calligraphy" pens map onto these modes; the exact mapping is **[open]**.

### 5.5 `figure` (`cf.l`)

`0` none (freehand), `1` line, `2` rectangle, `3` rhombus, `4` ellipse, `5` triangle, `6` polygon,
`7` polyline.

### 5.6 `dashtype` (`cf.i`)

`0` solid, `1` dash, `2` dot.

Render hint (`qm.j`/`xm.l`): with stroke width w, a dash is w·1.8 on and w·2.8 off with square
caps. A dot is a zero-length dash every w·1.5 with round caps.

### 5.7 `shapeType` (`cf.t`)

`0` shape (generic), `1` ellipse, `2` isosceles triangle, `3` rectangle, `4` polygon, `5` line,
`6` curve, `7` arrow, `8` polyline, `9` freeform polygon, `10` polyline-curve, `11` heart.

### 5.8 Masking `patternType` (`cf.s`)

`0` check, `1` stripe, `2` dot.

### 5.9 PDF annotation subtype values (`com.flexcil.androidpdfium.PdfAnnotationSubTypes`, first number)

`0` unknown, `1` text, `2` link, `3` freetext, `4` line, `5` square, `6` circle, `7` polygon,
`8` polyline, `9` highlight, `10` underline, `11` squiggly, `12` strikeout, `13` stamp, `14` caret,
`15` ink, `16` popup, `17` file attachment, `18` sound, `19` movie, `20` widget, `21` screen,
`22` printer mark, `23` trap net, `24` watermark, `25` 3D, `26` rich media, `27` XFA widget.

These match pdfium's `FPDF_ANNOT_*` numbering.

### 5.10 Folder `color` names (`ql.j2.Z`; hex from resources `folder_color1..9`)

| Name | Hex |
|---|---|
| Pink | #FF375F |
| Red | #FF453A |
| Orange | #FF9F0B |
| Yellow | #FFD60A |
| Green | #30D158 |
| Skyblue | #64D1FF |
| Blue (default) | #0A84FF |
| Violet | #5E5CE6 |
| Purple | #BF5AF2 |

An unknown name falls back to palette index 0. **[inferred]**

### 5.11 Hyperlink `actionType` (`cf.o`)

`-1` none, `0` unsupported, `1` go-to page, `3` URL, `4` launch.

### 5.12 Outline `actionType` (`cf.r`)

`-1` none, `0` unsupported, `1` go-to, `2` remote go-to, `3` URI, `4` launch.

### 5.13 File-item `state` (library only, `cf.m`)

`"valid"`, `"invalid"`, `"removed"`, `"downloading"`.

---

## 6. Coordinate systems

1. **Page frame** (`pages.index` `frame`): PDF points of the *unrotated* source page. The displayed
   size is the frame size, with width and height swapped when `rotate` is 90 or 270.
2. **Object space.** This covers every object coordinate, width, rect and font size in the
   `objects/*` files. **[read]**
   - **Normalised to the displayed page width:** 1.0 = the page's width as displayed, after `rotate`.
   - **Axes:** origin at the displayed page's top-left, x to the right, y downwards.
   - **Range:** x runs 0…1 and y runs 0…(displayedHeight/displayedWidth).
   - **Converting to PDF points of the displayed page:** multiply everything (x, y, widths, rects,
     font sizes) by the displayed page width in points.

   Evidence:
   - When a stroke is committed, its coordinates are divided by the page view's width
     (`AnnotationPDFView` → `new jf.a(path, viewSize, true)`).
   - The `jf.a`/`jf.i`/`jf.o`/`jf.p`/`jf.b` constructors that take a size scale every coordinate by
     `size.width`.
   - PDF export multiplies by the pdfium page width (`qg.r.b/f/g`).
   - `kf.h.a` clamps an object frame to `[0, 1−w] × [0, H/W − h]`.
   - When a page is rotated, `jf.a.k0`, `jf.i.i0` and `jf.o.B` re-map points to the new orientation
     and re-normalise by the width ratio, so objects always live in displayed-orientation space.
3. **Image crop** (`cropBox`, `freeCrops`): normalised 0..1 relative to the uncropped image.
4. **Angles:** page `rotate` is in degrees. Object `rotate` (drawings, shapes, images, text boxes,
   maskings) is in radians.

---

## 7. Importer pitfalls (checklist)

- **Names differ from the models.** Use the adapter names on disk: `paths` (masking annotations),
  `p`/`span`/`font-size`/… (text boxes), `points` as a base64 *string* (not an array),
  `actionType` (camelCase).
- **Two point-blob layouts.** Strokes, shapes and maskings use 12-byte (x, y, w) records relative to
  `start`. Text-markup `paths` use 8-byte (x, y) absolute records. Both start with an int32 count.
- **Colours are unsigned.** Mask to 32 bits.
- **Mixed date units.** Seconds are doubles and may use exponent notation; masking `createDate` is
  milliseconds and is the save time.
- **`_back` files.** Prefer the main file and fall back to `_back` when it's missing or empty.
  Expect concatenated `][` arrays in damaged files.
- **Draw order.** `.objects` is the order. Some objects may be missing from it.
- **Missing background.** A page without `attachmentPage` is blank. Pages share PDFs: many pages can
  point at the same attachment with different `index` values.
- **Hidden PDF annotations.** Hide the listed `annotationSubtypes` when rendering the background PDF,
  otherwise you'll double-draw annotations Flexcil already converted into objects.
- **Passwords.** PDF passwords are in `info.attachments`. They're unusable if `copyright` is set.
- **The real hierarchy is in `.itemInfo`.** Folder and document names on disk are sanitised and
  truncated.
- **No ordering.** Sort by name or date yourself.
- **Key collisions.** Keys are uppercase UUIDs. Flexcil regenerates all of them on restore, and Nxtes
  may want to as well, keeping the cross-references consistent:
  - `info.attachments` keys ↔ files in `attachment/PDF` ↔ `pages.index` `attachmentPage.file` ↔
    `template.info` `attachmentKey`
  - page keys ↔ `objects/<pageKey>.*` ↔ `currentPage` ↔ `outlines.link` ↔ `bookmark.pageKey` ↔
    hyperlink `link` ↔ `.rinfo`/`.rsync` page keys
  - image object `key` ↔ `attachment/image/<key>`
  - object keys ↔ `.objects` ↔ `.rsync` `obj` ↔ reference URIs
  - audio keys ↔ `audiorecord.refs` ↔ `.fab` entry names
- **Zip compatibility.** Both levels are ordinary ZIPs. Entries are DEFLATE level 0, which any
  inflater handles. Expect a leading `flexcilbackup/` in `.flex` and none in `.flx`.

---

## 8. Illustrative examples (constructed from the schema)

A `pages.index` with one template page and one page from an imported PDF:

```json
[
  {
    "frame": {"x": 0.0, "y": 0.0, "width": 595.0, "height": 842.0},
    "rotate": 0.0,
    "attachmentPage": {"file": "0B6F3C1E-9D3A-4E7B-8C21-5A1F2E3D4C5B", "index": 0},
    "annotationSubtypes": [2],
    "key": "A1B2C3D4-0000-4000-8000-000000000001",
    "version": "0.0.5"
  },
  {
    "frame": {"x": 0.0, "y": 0.0, "width": 612.0, "height": 792.0},
    "rotate": 90.0,
    "attachmentPage": {"file": "F00DCAFE-1234-4321-ABCD-0123456789AB", "index": 3},
    "annotationSubtypes": [2, 15, 9, 10],
    "key": "A1B2C3D4-0000-4000-8000-000000000002",
    "version": "0.0.5"
  }
]
```

`objects/A1B2C3D4-0000-4000-8000-000000000001.drawings`: one black ballpen stroke of three points.

```json
[
  {
    "figure": 0,
    "points": "AwAAAAAAAAAAAAAApptEOwrXIzwK16M7F7dROwrXozxvEoM7pptEOw==",
    "start": {"x": 0.25, "y": 0.4},
    "mode": 1,
    "dashtype": 0,
    "fillColor": 0,
    "strokeColor": 4278190080,
    "key": "5E8D2A10-7C4B-4F0E-9A63-2B1C0D9E8F70",
    "type": 1,
    "scale": {"x": 1.0, "y": 1.0},
    "rotate": 0.0
  }
]
```

The blob decodes to count 3, then (0, 0, 0.003), (0.01, 0.005, 0.0032), (0.02, 0.004, 0.003).

- **Absolute points:** (0.25, 0.40), (0.26, 0.405), (0.27, 0.404) in page-width units.
- **On an A4 page 595 pt wide:** the first point is at (148.75 pt, 238 pt), and the width is about
  1.8 pt.
- **Colour:** `4278190080` = `0xFF000000`, opaque black.

The matching `objects/…0001.objects` is
`[{"type":1,"key":"5E8D2A10-7C4B-4F0E-9A63-2B1C0D9E8F70"}]`.

A text-highlight `paths` entry for one line: `{"points":"AgAAAM3MzD3NzEw+PQrXPs3MTD4=","width":0.018}`.
It decodes to 2 points, (0.10, 0.20) → (0.42, 0.20), absolute.

---

## 9. Open questions (need a real backup or the iOS app)

1. **Shape vertex conventions** for rectangle, ellipse, isosceles triangle, polygon and heart. I
   don't know whether `points` holds 2 bounding corners or every vertex. The same goes for arrow head
   geometry. A backup with one shape of each type would settle it.
2. **Sticky note `color` and `icon`.** I don't know whether `color` is ARGB or a palette index, or
   what the icon ids are.
3. **Background of a page without `attachmentPage`.** It's presumably white; check a page made with
   "add blank page".
4. **Rotated pages.** I'd like confirmation that object coordinates on a page with `rotate` = 90 are
   normalised to the rotated (landscape) width, as the code implies. A backup with one rotated page
   and one stroke would settle it.
5. **`rotate` on square-highlighter and slanted-fountain strokes:** whether the stored points are
   pre-rotated (like other pens) or rotated at draw time.
6. **Exact brush rendering** for fountain, texture and calligraphy pens, and how `seed`/`phase` are
   used. The rendering engine is partly native and in the missing split APK.
7. **iOS-made backups.** The URL-decoding of `.itemInfo`, `scale` ≠ 1 and the old
   `annotationSubtypes` semantics all hint at other writers. I don't know whether an iOS backup uses
   the same root `info` (`appName`) and `version` ≥ 4. One iOS `.flex` would settle it.
8. **Calligraphy pens.** I don't know how the four calligraphy scripts map onto stored `mode` values
   (they may be presets of fountain or texture pens).
9. **Text-box style tokens.** I don't know the full set of `font-style` values (strikethrough?) or
   whether `font-family` lists fallbacks.
10. **Files I didn't parse here:** document-level `.itemInfo` `favorite` on restore (M0 doesn't read
    it; it may be read elsewhere), and the planner-specific `additional` contents. Neither looks
    needed for import.
