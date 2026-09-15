# Earning cards update

All three cards have a 124dp minimum height, native titles, and larger reward chips. Offers and Tournments has the cleaned phone/star/trophy background and a gold star before Earn Stars. All existing tap destinations are retained.

The debug build succeeded. Native offline previews were rendered at 320dp and 390dp widths with 1.0 and 1.5 font scales. The temporary render fixtures were restored to their original contents afterward. The debug build was installed on the connected Android device without clearing app data.

## Artwork

Saved asset: `app/src/main/res/drawable-nodpi/home_offers_background.png`.

Created with the built-in image-generation tool using this edit prompt:

> Edit target: attached blue game rewards card. Produce ONLY its background illustration for use behind native Android UI. Preserve the same glossy purple phone with gold star, gold trophy, electric-blue glow and cyan sparkles. REMOVE ALL text (Earn and Offers & Tournaments), REMOVE circular arrow button entirely, REMOVE outer rounded border and screenshot margin. Fill removed areas seamlessly with blue background. No letters, no numbers, no UI buttons, no borders. Full-bleed rectangular background, aspect ratio 1:1. Preserve the existing phone and trophy design and relative arrangement, centered horizontally in upper 55% of canvas. Lower 45% smoothly fades to solid dark navy #081C51, empty for native labels and chips. No new objects. This is a clean background asset, not a finished card mockup.
