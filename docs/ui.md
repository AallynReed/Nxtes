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
  Settings at the bottom.
- **Title bar:** a back button to the parent folder on the left, the folder name centred, and
  selection mode on the right.
- **Selection mode:** "Select all" on the left and Done on the right until something is picked;
  then "× N items selected" on the left and merge (two or more documents), duplicate, move and
  move to trash on the right. Each item shows a circle with its place in the selection, which is
  also the merge order. In the trash the actions are Restore and Delete.
- **Filter row:** All, PDFs and Notes, centred; sort and grid/list on the right.
- **Grid cell:** a coloured folder icon, the note's cover, or the first-page thumbnail, with a
  favourite star in its top-right corner. Below it the name with a ▾ menu (rename, cover and
  template, move, duplicate, colour, favourite, move to trash), then a line with the item count
  or file size and the date (`yyyy.MM.dd`).
- **Add button:** bottom right. It opens a labelled menu: New folder, New note, Import PDF.
- **Trash** opens in the same main area, with Restore all and Empty in the title bar.

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

- Tapping the Cover or Template preview switches the list below. Covers are generated colours and
  gradients, plus "First page" for none.
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
    colours. The selected pen sits raised; tapping it again opens its settings. After a "+" to
    add a pen come the eraser and the other tools as they're built (lasso, shapes, text, image,
    sticker, laser).
  - Right: undo, redo, view settings, and the side panel button.
- **Modes.**
  - Pen mode draws with the stylus, and with a finger too unless "Use stylus only" is on.
    Fingers always scroll and zoom with two fingers.
  - Gesture mode never draws; pen and fingers scroll, zoom and select.
- **Tab bar (36 dp).** One tab per open document with a close button. The active tab is
  underlined in the accent colour, and the open-document count sits on the right. Going back
  to the library keeps the tabs.
- **Side panel (320 dp, from the right, over a dimmed page).**
  - The current tab's name and a close button on top, then the tab icons: search, pages,
    outline, bookmarks, annotations. Tabs whose feature isn't built yet stay hidden.
  - Pages: a page number field with Go, then a thumbnail grid; tapping a page jumps to it.
  - Outline: the PDF's own entries and the user's, in page order. Bookmarks: title and page.
    Both have an add row at the bottom; touch and hold a user entry to edit or delete it.
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
