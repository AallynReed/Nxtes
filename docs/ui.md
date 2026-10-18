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
- **Filter row:** All, PDFs and Notes, centred; sort and grid/list on the right.
- **Grid cell:** a coloured folder icon or the first-page thumbnail, with a favourite star in its
  top-right corner. Below it the name with a ▾ menu (rename, move, colour, favourite, move to
  trash), then a line with the item count or file size and the date (`yyyy.MM.dd`).
- **Add button:** bottom right. It opens a labelled menu: New folder, New note, Import PDF.
- **Trash** opens in the same main area, with Restore all and Empty in the title bar.

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
  - Left: back to the library, and the page navigator (opens the side panel on its pages tab).
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
  - Tabs: search, pages, outline, bookmarks, annotations.
  - The pages tab shows a thumbnail grid; tapping a page jumps to it.
  - Tabs whose feature isn't built yet stay hidden.
- **Page background:** pages sit on light grey with a thin border, and a "3 / 17" counter at
  the bottom.
