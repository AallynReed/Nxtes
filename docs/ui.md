# Nxtes UI

Nxtes keeps the screen layout and workflow that Flexcil users already know, so they can switch
without relearning where things are. Icons, artwork and wording are Nxtes's own.

Sizes are for tablets (smallest width 600 dp and up). Phones get the same structure with the
library menu as a bottom bar.

## Colours

| Role | Light | Dark |
|---|---|---|
| Bars, menus, cards | white | near-black |
| Page background | light grey `#F3F3F3` | black |
| Accent (selection, active tab, active tool) | blue `#4793EF` | amber `#F2B84B` |
| Dividers | `#DDDDDD` | `#333333` |

## Library

```
+-----------+------------------------------------------------+
| [Search]  |  < Parent        Documents               [✓]  |
|           |           All   PDFs   Notes       [sort][≡] |
| Documents |  +------+  +------+  +------+                  |
| Trash     |  |folder|★ | page |★ | page |★                 |
|           |  +------+  +------+  +------+                  |
| Recents ★ |  Name   ▾  Name   ▾  Name   ▾                  |
| Favorites |  3 items·date 1.2 MB·date ...                   |
|  thumb  A |                                                |
|  thumb  B |                                        (+)     |
| Settings  |                                                |
+-----------+------------------------------------------------+
```

- **Left menu (240 dp):** a search box at the top; Documents and Trash; a Recents/Favorites
  switch with a "Show all" button and the matching list of documents (thumbnail + title);
  Settings at the bottom: back up the library to a zip, restore one (adding only what's missing),
  and the version.
- **Title bar:** a back button to the parent folder on the left, the folder name centred, and
  selection mode on the right.
- **Selection mode:** "Select all" on the left and Done on the right until something is picked;
  then "× N items selected" on the left and merge (two or more documents), duplicate, move and
  move to trash on the right. Each item shows a circle with its place in the selection, which is
  also the merge order. In the trash the actions are Restore and Delete.
- **Filter row:** All, PDFs and Notes, centred; sort and grid/list on the right.
- **Grid cell:** a coloured folder icon, the note's cover, or the first-page thumbnail, with a
  favourite star in its top-right corner. Below it the name with a ▾ menu (rename, cover and
  template, export, move, duplicate, colour, favourite, move to trash), then a line with the item count
  or file size and the date (`yyyy.MM.dd`).
- **Add button:** bottom right. It opens a labelled menu: New folder, New note, Import PDF or
  image. Images become one-page documents; PDFs and images can also be shared to Nxtes.
- **Trash** opens in the same main area, with Restore all and Empty in the title bar.
- **Search:** typing in the search box shows Titles (matching names) and Inside documents
  (pages whose PDF text, text boxes, sticky notes or highlights contain every word typed, each
  as a word start). Content results are grouped by document with the page and a snippet; tapping
  one opens the document at that page.

## Note settings

Full screen, for New note and for an item's "Cover and template".

```
+------------------------------------------------------------------+
| x                                                        [Done]  |
|  +-----+   +-----+                                               |
|  |cover|   |templ|            ______Note title______             |
|  +-----+   +-----+                                               |
|   Cover    Template                                              |
| Templates  Custom                     A4 v   [ ]   o o o         |
|------------------------------------------------------------------|
| Basic                                                            |
|  [plain] [lined] [dotted] [graph] [legal] [cornell] [vertical]   |
| Two columns                                                      |
|  [plain] [lined] [dotted] [graph]                                |
+------------------------------------------------------------------+
```

- Tapping the Cover or Template preview switches the list below. Covers are an image picked from
  the gallery (cropped to fill, kept in the document), generated colours and gradients, plus "First
  page" for none.
- The template row holds paper size, orientation and paper colour (white, yellow, dark). The
  Custom tab lists templates made from an image or a PDF, with an Add tile; touch and hold one to
  delete it.
- Changing an existing note's template asks whether it applies to new pages only or to every page.

## Writing view

```
+------------------------------------------------------------------+
| <  ⊞       [✋/✍] │ pens… [+] │ eraser  tools…      ↶ ↷   ⚙  ☰   |
+------------------------------------------------------------------+
|  Doc A   ×  |  Doc B   ×  |  Doc C (selected, underlined)  ×  | 3 ▾ |
+------------------------------------------------------------------+
|                  grey page background                            |
|             +--------------------------+                         |
|             |          page            |          side panel →   |
+------------------------------------------------------------------+
```

- **Toolbar (48 dp, white).**
  - Left: back to the library, and the page navigator.
  - Centre: the pen/gesture mode switch, then the pens as small upright pen glyphs in their
    colours. The selected pen sits raised; tapping it again opens its settings. Every colour
    choice (pens, text, notes, masking, laser, folders) offers its own colours, the saved palettes,
    and a custom colour from a shade square, hue bar or hex code that can be added to a palette. A shape pen is a
    pen type with a shape, line style, fill and polygon sides. After a "+" to add a pen come the
    eraser, lasso, image, text box, sticky note, sticker, masking and laser. Tapping a selected
    tool opens its settings.
  - Right: export, the microphone, undo, redo, view settings, and the side panel button. In read-only mode the tools
    give way to a "Read only" pill that turns it off.
- **Recording:** the microphone button left of undo opens Record (or Pause/Resume and Stop while
  recording) and the document's recordings, each with play and a ⋮ menu to rename or delete.
  While recording, the button turns red and a pill at the top left of the pages shows the time
  with Pause and Stop; recording carries on in the background with a notification that can stop
  it. Playing a recording opens a bar above the page counter: play/pause, the title, a seek bar
  with times, speed (0.5× to 2×) and close. While it's open, writing done during the recording
  appears as it was written, and tapping ink with a finger plays from when it was written.
- **Export:** PDF or pictures. A PDF keeps the original PDF pages with the writing on top (sticky
  notes become PDF comments), or flattens each page into a picture. Checkboxes pick what to
  include (background and PDF, handwriting with shapes and highlights, images and stickers, text
  boxes, sticky notes, masks) and the pages (all, this one, or a range like 1–3, 5). Save writes a
  PDF where you choose, or pictures to Pictures/Nxtes; Share opens the share sheet.
- **View settings:** page layout (scroll down or across, continuous or page by page, two pages
  side by side, first page alone); dark pages, keep the screen on, full screen, hide the tab
  bar; use stylus only, show annotations, read only; and which shapes holding the pen still
  makes.
- **Modes.**
  - Pen mode draws with the stylus, and with a finger too unless "Use stylus only" is on.
    Fingers always scroll and zoom with two fingers.
  - Gesture mode never draws; pen and fingers scroll, zoom and select. Touch and hold PDF text
    to pick it.
  - Read-only mode changes nothing: touches scroll, zoom and reveal masks, and the laser works.
  - In any mode a two-finger tap undoes and a three-finger tap redoes.
  - Page by page turns one page (or pair of pages) per swipe.
- **Tools.**
  - Lasso: freehand or box, picking up the item types ticked in its settings. The selection gets
    a dashed box with corner handles to resize and a handle above to turn, and a menu above it:
    colours, then copy, cut, bring to front, send to back and delete, plus edit text, crop, open
    note or add to stickers when they apply.
  - Touch and hold an item to select it; on an empty spot it opens paste, image, text box, add
    bookmark and add to outline.
  - Hold the pen still at the end of a line to turn it into a line, circle, triangle, rectangle
    or polygon.
  - Text box: tap to place one; a bar above it has font (with imported fonts), size, colour,
    bold, italic, underline, background and delete.
  - Image: from the gallery, files or the camera; crop to a box or freehand from the selection
    menu.
  - Sticky note: tap to place one; it opens a card with colours, delete and its date. Tap a
    note's icon to open it again.
  - Sticker: tap to stamp the sticker picked in its panel: recent ones, the built-in basics, and
    the user's packs, which take images from the gallery. Touch and hold a pack or sticker to
    rename or delete it.
  - Masking: tape boxes or pen lines in a choice of colours. Tapping a mask reveals it and asks
    whether the answer was known; tapping again covers it.
  - Laser: a dot or a fading line in a choice of colours, never saved.
  - Picked PDF text gets handles at both ends and a menu: highlight colours, underline, strike
    through, copy, look up, share, add bookmark and add to outline. Touch and hold a highlight to
    recolour, copy or delete it.
  - A highlighter stroke drawn mostly over PDF text snaps to that text and becomes a highlight in
    the pen's colour, as in Flexcil; elsewhere it stays ink. One undo removes it.
- **Tab bar (36 dp).** One tab per open document with a close button. The active tab is
  underlined in the accent colour, and the open-document count sits on the right; it lists the
  open documents and offers Close other tabs and Close all tabs, as does touching and holding a
  tab. Going back to the library keeps the tabs. View settings can hide the bar.
- **Side panel (320 dp, from the right, over a dimmed page).**
  - The current tab's name and a close button on top, then the tab icons: search, pages,
    outline, bookmarks, annotations, masking. Back closes the panel.
  - Search: a search field, the match count, then matches grouped by page, each a snippet with
    the match in bold and where it was found (PDF text, text box, sticky note, highlight).
    Tapping one scrolls it into view and marks it on the page until the next tap.
  - Pages: a page number field with Go, then a thumbnail grid; tapping a page jumps to it.
  - Outline: the PDF's own entries and the user's, in page order. Bookmarks: title and page.
    Both have an add row at the bottom; touch and hold a user entry to edit or delete it.
  - Annotations: filter chips (all, highlights, text boxes, sticky notes, images and stickers,
    shapes, handwriting), then everything on the document grouped by page; tapping a row goes to
    its page.
  - Masking: the document's correct rate, sort by page, highest or lowest score, reveal all and
    cover all, then each mask with its page and score.
- **Page background:** pages sit on light grey with a thin border, and a "3 / 17" counter at
  the bottom. Tapping the counter opens Go to page.

## Page navigator

Opened from the toolbar's page navigator button; it covers the writing view.

```
+--------------+---------------------------------------------------+
| < Parent     | =<            Document title          /  [Close]  |
| Folder name  |                 All   Annotated                   |
| doc   thumb  |  + [1] + [2] + [3] + [4] + [5]   [ + ]            |
| doc   thumb  |                                                   |
| Docs Rec Fav |                                                   |
+--------------+---------------------------------------------------+
```

- **Left (288 dp):** documents in the current folder, recents or favourites (tabs at the
  bottom); tapping one shows its pages on the right. The top-left button hides the column.
- **Pages:** thumbnails with the page number at the bottom; the current page is outlined in the
  accent colour. "+" between pages and the dashed tile at the end open a menu: blank page, page
  from another template, page from an image, pages from a PDF, paste copied pages.
- **Edit mode** (the pencil, or touch and hold a page): "× N pages selected" and Select all on the
  left; change template, turn left, turn right, move to another document, duplicate, copy and
  delete on the right. Drag a page to reorder it, or onto a document on the left to move it.
