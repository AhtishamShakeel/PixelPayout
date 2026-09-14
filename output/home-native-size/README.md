# Home at normal app size

Restored the original shared 72dp title bar and the standard PixelBottomNav on Home. The special Home header/navigation layouts and switching logic have been removed. The shared header, balance and navigation code now match the original app implementation.

Stars and Levels remain side by side, as requested, with matching 220dp minimum heights and 44dp action buttons. Home uses 16dp outer margins, 12dp section gaps, native sp text, taller bonus rows, and a larger seven-day check-in strip. The approved colors, right-side reward art, level shield and earning flow remain. Quick-action captions are native text with the reference artwork above them; small lettering is no longer baked into the resized card image.

Reward selection, Firebase artwork, redemption, level claims, daily-goal progress and check-in handlers remain connected to the existing app logic. The UC artwork will still come from Firebase; the previews use the PNG prepared in `../home-reference/uc-firebase.png` as sample artwork.

Files:
- `PixelPayout-home-native-size-debug.apk`: updated debug app.
- `screen.png`: 390dp-wide native viewport preview with the shared app bars and sample data.
- `preview.png`: full scroll-content preview with sample data.

Validation: debug and instrumentation builds succeeded. Native layout checks passed at 320dp and 390dp widths, 1.0 and 1.5 text scales, and both normal and claimable reward states. Checks verify equal card heights, side-by-side placement, 44dp actions, non-overlapping progress bars, unclipped text and the original 72dp header. Reward network calls and ad claims were not triggered by these offline renders.
