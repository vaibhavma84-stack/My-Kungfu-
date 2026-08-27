# Letterhead

Kept here so it survives, and so a future rebuild does not have to go digging
through a .docx again.

| File | |
|---|---|
| `synergy-logo.png` | the Synergy Group mark, 168x162 PNG with transparency — the original, extracted from `word/media/image120.png` inside the Weekly Work Done template, not a screenshot |
| `letterhead-header.png` | the header band as it appears in Word, for reference |
| `letterhead-footer.png` | the footer rule and page number, for reference |

## Colours, read off the template

| | |
|---|---|
| Navy bar | `#002060` |
| Rule under the header | `#29ABE2` |
| Caption band on photo rows | `#D9D9D9` |

## How the app uses it

`synergy-logo.png` is inlined as a base64 data URL in `index.html`, in the
`SYNERGY_LOGO` constant, because the app has to work with no network. If the
logo ever changes, re-encode it and replace that constant:

```bash
base64 -w0 brand/synergy-logo.png
```

## Two things the template has that the app does not

**The right-hand slot in the header** holds a broken-image placeholder in the
source document — a camera icon, not a photograph. Nothing was carried across.
If a ship photo is wanted there, drop it in this folder and it can be added.

**The footer page number** ("1 | Page") is a Word PAGE field. A browser cannot
generate one inside the page content, so the app's report has no page numbers.
Android's print dialogue can add them under its own headers-and-footers option.
