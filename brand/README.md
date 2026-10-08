# CoxTV brand assets

One master design for every icon: the **C monogram** (an open orange C around a play
button, on a dark rounded tile) and the **CoxTV** wordmark (white "Cox", orange "TV").

- Orange `#FF8200`, near-black `#0A0A0B`, tile `#141416`, text `#EDEDED`
- Type: Geist and Geist Mono (SIL Open Font License)

`render.js` draws every asset with headless Chrome into `out/`:
Android/Fire TV launcher layers (`fg-*`, `bg-*`, `legacy-*`), Fire TV banners (`banner-*`),
Roku poster, splash, in-app monogram and star (`roku-*`), and the website's favicons and
home-screen icons.

To run it (Windows, Chrome installed):

```
cd brand
npm pack geist && tar xzf geist-*.tgz     # Geist fonts into ./package
node render.js
```

Then copy the files from `out/` to `app/` and `mobile/src/main/res`, `roku/images` and
the website's `static/icons`.
