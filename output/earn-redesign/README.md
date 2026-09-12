# Earn page redesign

Implemented a native Android Earn page based on D:/earn.jpeg: purple tournament hero, trophy artwork, separate weekly XP and prize-pool panels, gradient actions, three offer steps, and illustrated catalogue cards. The page scrolls as one surface. Shared header and bottom navigation retain their existing implementation.

Only presentation and its data bindings changed. Weekly XP comes from Leaderboard.myXp; rank, pool, and paid positions still come from the same server snapshot. Offerwall availability, level filtering, Tapjoy SDK handling, hosted-wall navigation, and reward logic are unchanged. The existing MainActivity edits were left alone.

## Verification

- Debug APK and Android test APK build successfully.
- EarnLayoutTest passes directly through AndroidJUnitRunner on Pixel_9_Pro. Its four cases cover 320dp and 390dp widths at 1.0 and 1.5 font scale, text clipping, scrolling, minimum tournament-button height, and correct offer selection.
- preview.png, compact.png, and large-text.png are native layout renders using offline sample data. They do not represent the current account balance or live offer availability.
- The actual Earn screen was also inspected in the emulator with its loading and empty states. A later live navigation check could not be completed because the app returned to onboarding; no login or account changes were attempted.
- Full lint is blocked by two pre-existing errors: NewApi for forceDarkAllowed in res/values/themes.xml:24 and UseAppTint in res/layout/activity_offerwall.xml:44. Neither file was changed.
- No backend deployment is needed.

## Artwork

Generated with the built-in image-generation tool. Transparent originals are copied into the project at:

- D:/android studio projects/PixelPayout/app/src/main/res/drawable-nodpi/earn_tournament_art.png
- D:/android studio projects/PixelPayout/app/src/main/res/drawable-nodpi/earn_offers_art.png

### Tournament prompt

Use case: stylized-concept. Create a production Android app illustration asset, square 1024x1024, genuine transparent background. Glossy premium 3D golden trophy with raised five-point gold star on cup, standing on the tall center purple podium marked 1, shorter purple podiums marked 2 left and 3 right. A few floating gold stars and violet/gold confetti. Front view slightly from above. Brilliant violet soft rim lighting, smooth bevelled plastic podium and polished warm gold. Compact centered composition filling frame with 5 percent safe margins. No backdrop, no text other than podium numbers, no UI, no watermark. Match a playful luxury gaming rewards app in deep navy and vivid purple.

### Offers prompt

Use case: stylized-concept. Production Android app illustration asset square 1024x1024 with genuine transparent background. Glossy premium 3D gaming rewards still life: upright slightly tilted smartphone in dark violet frame displaying three large colorful generic app icons (play triangle, gamepad, survey lines); purple game controller in front left, lilac clipboard with three purple checkboxes front right, gold star coin behind controller and floating gold stars. Resting on low violet round plinth. Front view slightly from above, compact centered composition, 5 percent safe margins. Vivid purple rim lighting, polished gold and smooth bevelled plastic, playful premium game style. No background, no words, no UI, no watermark.
