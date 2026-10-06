# Install page screenshots

Each dashed box on the install page (`docs/index.html`) names the file it expects here, e.g.
`firetv-downloader.png`. To add one, save the screenshot here with that name and replace the
placeholder in `index.html`:

```html
<figure class="shot">Screenshot: ... <span>docs/images/firetv-downloader.png</span></figure>
```

with

```html
<img class="shot-img" src="images/firetv-downloader.png" alt="Downloader in the Amazon Appstore">
```

Avoid screenshots that show your own playlist link, channel lineup or location.