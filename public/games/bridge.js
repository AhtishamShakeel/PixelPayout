/**
 * The bridge every hosted game uses to hand its final score back to the app.
 *
 * GamePlayActivity injects a `AndroidInterface` object with a single
 * `onGameComplete(score)` method, which forwards to GamePlayViewModel and
 * from there to the claimReward callable. Two things the games must respect:
 *
 *  - The claim is rejected server-side when a session is younger than
 *    MIN_SESSION_MS (3s in functions/src/economy/gameSession.ts). A player who
 *    dies almost immediately would otherwise lose the run to a `too_fast`
 *    rejection, so the report is held back until the session is old enough.
 *  - A session is single-use, so a second report is dropped rather than
 *    attempted.
 *
 * Opening the page in a plain browser is fine: with no AndroidInterface the
 * report is a no-op, which is what makes these testable outside the app.
 */
(function (global) {
  var MIN_SESSION_MS = 3500;
  var loadedAt = Date.now();
  var reported = false;

  global.PixelPayout = {
    reportScore: function (score) {
      if (reported) return;
      reported = true;

      var value = Math.max(0, Math.floor(Number(score) || 0));
      var wait = Math.max(0, MIN_SESSION_MS - (Date.now() - loadedAt));

      setTimeout(function () {
        if (global.AndroidInterface && global.AndroidInterface.onGameComplete) {
          global.AndroidInterface.onGameComplete(value);
        } else {
          console.log('[PixelPayout] no host bridge; score would be', value);
        }
      }, wait);
    }
  };
})(window);
