# Home reference redesign

- `PixelPayout-home-reference-debug.apk`: updated debug app.
- `preview.png`: native Android render using sample data and the prepared UC artwork.
- `preview-claimable.png`: native render with reward, level, daily bonus and check-in claim controls visible.
- `uc-firebase.png`: transparent UC illustration for your Firebase upload. Set the selected game's `currencyImageUrl` to its uploaded URL. The app continues reading that field, with `imageUrl` as its fallback. No Firebase content was changed.

The layout uses the reference's 1024-unit coordinate system, scaled to screen width. The Stars and Levels cards retain the 465:325 proportions. Artwork is on the right of Stars; its selected reward amount, golden unlock cost, progress, Change control and redeem action remain live. The level number is native text over a blank illustrated badge. The next level's star bonus has no pill background.

The guide, quick-action artwork and Home navigation artwork reuse the supplied reference through native drawing. Buttons retain app navigation and accessibility labels. Bonus rows use actual daily goals, with progress rings around their illustrations. Check-in and reward claims retain their existing handlers. Other tabs retain their existing header and navigation appearance.

The preview uses sample values; it is not a signed-in account screenshot. In the installed app, the UC image remains whatever Firebase currently provides until you upload the new PNG. The existing app avatar and real payout initials remain account-appropriate. Generated UC and blank level artwork are recreations of the reference illustrations.

Validation: debug app and instrumentation builds; existing unit tests (7 passing); native layout checks at 320dp and 390dp, normal and 1.5 font settings, normal and claimable states (8 combinations). Checks cover matching card proportions, artwork placement and unclipped text. The intentionally dense design uses reference-scaled text as requested. No rewarded ads or live claims were triggered in these offline checks.

## Generated artwork

Full-project lint still reports two pre-existing errors outside the redesign: `themes.xml` uses `forceDarkAllowed` without an API 29 resource qualifier, and `activity_offerwall.xml` uses `android:tint` instead of `app:tint`. There are no Home lint errors. These do not prevent APK assembly.

Created with the built-in image generation tool. Final assets are `uc-firebase.png` and `app/src/main/res/drawable-nodpi/home_level_art.png`.

UC prompt:

> Use case: background-extraction. The attached image is the reference. Deliver ONLY the blue UC currency illustration from the upper-left reward card as a standalone high-resolution square PNG with genuinely transparent background. Preserve the reference object's exact appearance: three overlapping tilted blue UC credit cards, bright silver beveled borders, bold white UC letters on front, glowing cyan elliptical pedestal/ring below, small blue sparkling stars, intense electric blue glow. Same perspective, shapes, spacing, color and orientation as the reference. Tight composition with only 5% padding. Do not include any surrounding app UI, text other than UC, panel background, characters, phones, coins, or additional objects. This asset will replace the Firebase reward artwork, so alpha must be transparent around the glow. Match the supplied illustration as faithfully as possible.

The level asset was generated from the reference as a blank purple/silver shield with wings and no number, so the real level is drawn above it. Final corrective prompt:

> Replace ALL checkerboard with a uniform deep dark purple background, exact hex #190E36. Preserve the centered purple blank shield and wings exactly as-is. Violet glow may softly fade into the dark background. There must be zero gray or white squares. Solid dark purple background right to the edges, no transparency needed. Do not include a numeral or text. This is an image cleanup, NOT a new design.
