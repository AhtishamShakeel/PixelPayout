# Home redesign

Native Android implementation based on `D:/homee.jpeg`. The shared greeting, avatar, star balance and navigation remain provided by MainActivity.

- Equal-height reward and level cards, violet gradient actions and dark blue/violet surfaces.
- The next reward amount is prominent. Its star cost is gold. Artwork comes from the selected Firebase game's `currencyImageUrl`, falling back to `imageUrl` and then a gift icon. Change opens the existing picker.
- Redeem and level-claim buttons retain their existing availability checks, amounts and destinations. The level badge has a native vector background with a live TextView number. The next level's star bonus is gold on a transparent background.
- An earning guide and illustrated Games, Quizzes and Offers tiles appear before the daily activities. Offers still respect the existing availability flag.
- Today's bonus uses the actual daily goals, image-centered circular progress and task navigation buttons. No subscription task was added. Existing bonus/ad claim logic and settled-state behavior are retained.
- Daily check-in shows all seven days, reward types, the current day and claimed states. Its server reward table, retry handling and claim flow are unchanged.
- Below 360dp with enlarged text, dense illustration rows reflow vertically and the day strip scrolls horizontally to keep words readable. The main reward and level cards remain equal-height columns.
- Pending redemptions, active boosts, recent payouts and referrals remain available below the main sequence.

## Verification

Debug app and Android test APKs build successfully. All seven existing unit tests pass.

HomeLayoutTest renders eight cases: 320dp and 390dp widths, 1.0 and 1.5 font scales, with and without claimable rewards. It verifies matching card heights, aligned actions, 48dp touch-target heights, text bounds and scrolling. It uses offline sample data, including the existing UC artwork in test assets. No account, Firebase data or connected phone was modified.

`preview.png`, `compact.png`, `large-text.png` and `compact-large-text.png` are native offline renders of the Home content, excluding the shared toolbar/navigation. They illustrate sample data, not a live account.

Full Android lint remains blocked by two pre-existing errors: NewApi for forceDarkAllowed in `values/themes.xml`, and UseAppTint in `layout/activity_offerwall.xml`. Neither file is part of this change. Home retains warnings for compact captions and nested layouts.

## Generated artwork

Created with the built-in image-generation tool; transparent PNG originals are included in the application:

- `D:/android studio projects/PixelPayout/app/src/main/res/drawable-nodpi/home_games_art.png`
- `D:/android studio projects/PixelPayout/app/src/main/res/drawable-nodpi/home_quiz_art.png`

Game prompt: Use case: stylized-concept. Create a single isolated premium mobile gaming UI illustration: a glossy chunky purple video game controller tilted slightly, cyan and pink buttons, with two small violet lightning bolts and restrained sparkles. 3D rendered bevels, luminous violet rim lighting. Real transparent alpha background, no backdrop, no platform, no words, no letters, no numbers, no UI. Centered compact composition with 8% padding, readable at 64 pixels. Palette purple #7534FF, dark indigo, cyan accents. Save as a PNG.

Quiz prompt: Use case: stylized-concept. Single isolated mobile gaming UI illustration. Two chunky luminous turquoise 3D speech bubbles, one with a dark indigo question mark, behind it a teal jigsaw puzzle piece. Premium glossy beveled toy-like material, cyan rim lighting, tiny restrained sparkles. Centered compact composition with 8% padding, readable at 64 pixels. Real transparent alpha background, no backdrop, no platform, no UI, no words, no numbers. Palette teal #20DDD4, aqua and dark indigo. PNG.
