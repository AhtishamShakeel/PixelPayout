/**
 * Neon Flap - a one-tap endless flyer.
 *
 * Written from scratch for PixelPayout rather than adapted from one of the
 * open-source Flappy Bird clones: every one of those we looked at ships the
 * original game's sprite and audio rips, which we can't distribute. Nothing
 * here is loaded from disk - the bird, pipes, skyline and ground are drawn
 * with canvas primitives and the three sounds are synthesised with WebAudio -
 * so the whole game is one file with no assets and no dependencies.
 *
 * Score is "pipes passed", one point each. That scale is what the server's
 * floppy_bird rules already expect (maxScorePerSecond 2, XP divisor 1), so
 * this drops into the existing economy without retuning it.
 */
(function () {
  'use strict';

  var canvas = document.getElementById('game');
  var ctx = canvas.getContext('2d');

  var READY = 0, PLAYING = 1, DEAD = 2;
  var state = READY;

  var W = 0, H = 0, dpr = 1;
  // `scrolled` is monotonic total distance, never wrapped. Both the ground
  // stripes and the skyline take their own modulus of it at draw time. It used
  // to be stored pre-wrapped, which meant the skyline's parallax offset snapped
  // back to zero every time the (much shorter) ground stripe cycle rolled over
  // - about seven times a second. That was the shudder.
  var bird, pipes, scrolled, score, best, flashAlpha, deadAt;

  // Everything scales off canvas height so the game plays identically on a
  // tall phone and a squat tablet.
  var G, FLAP, PIPE_SPEED, PIPE_GAP, PIPE_W, PIPE_SPACING, BIRD_R;

  function resize() {
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    W = canvas.clientWidth;
    H = canvas.clientHeight;
    canvas.width = Math.round(W * dpr);
    canvas.height = Math.round(H * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    G = H * 2.4;
    FLAP = -H * 0.72;
    PIPE_SPEED = H * 0.42;
    // Tightened from 0.27. The gap is the whole difficulty dial for this
    // game, and at 0.27 a run barely ended - which matters more now that the
    // per-session XP ceiling is 60 rather than 30, since a game nobody loses
    // pays every player the maximum.
    PIPE_GAP = H * 0.22;
    PIPE_W = Math.min(W * 0.16, H * 0.11);
    PIPE_SPACING = Math.max(W * 0.62, H * 0.42);
    BIRD_R = H * 0.026;
  }

  function reset() {
    bird = { x: W * 0.28, y: H * 0.44, vy: 0, rot: 0 };
    pipes = [];
    scrolled = 0;
    score = 0;
    flashAlpha = 0;
    state = READY;
  }

  function spawnPipe(x) {
    var margin = H * 0.12;
    var groundY = H * 0.86;
    var top = margin + Math.random() * (groundY - PIPE_GAP - margin * 2);
    pipes.push({ x: x, top: top, passed: false });
  }

  // --- audio ---------------------------------------------------------------
  // Three short synthesised blips. No files, and no autoplay problem: the
  // context is created on the first tap, which is a user gesture.
  var audio = null;

  function blip(freq, dur, type, gain) {
    if (!audio) return;
    var osc = audio.createOscillator();
    var amp = audio.createGain();
    osc.type = type || 'square';
    osc.frequency.setValueAtTime(freq, audio.currentTime);
    amp.gain.setValueAtTime(gain || 0.06, audio.currentTime);
    amp.gain.exponentialRampToValueAtTime(0.0001, audio.currentTime + dur);
    osc.connect(amp);
    amp.connect(audio.destination);
    osc.start();
    osc.stop(audio.currentTime + dur);
  }

  function ensureAudio() {
    if (audio) return;
    var Ctx = window.AudioContext || window.webkitAudioContext;
    if (Ctx) audio = new Ctx();
  }

  // --- input ---------------------------------------------------------------
  function tap() {
    ensureAudio();

    if (state === READY) {
      state = PLAYING;
      var first = W + PIPE_SPACING * 0.6;
      for (var i = 0; i < 4; i++) spawnPipe(first + i * PIPE_SPACING);
      bird.vy = FLAP;
      blip(660, 0.07, 'square');
      return;
    }

    if (state === PLAYING) {
      bird.vy = FLAP;
      blip(660, 0.07, 'square');
    }

    // A tap on the game-over card is deliberately inert: the run is already
    // reported and the session it was claimed against is spent, so a second
    // run in the same WebView could never be paid out.
  }

  function die() {
    if (state !== PLAYING) return;
    state = DEAD;
    deadAt = performance.now();
    flashAlpha = 0.85;
    blip(180, 0.18, 'sawtooth', 0.09);
    setTimeout(function () { blip(110, 0.32, 'sawtooth', 0.08); }, 110);

    best = Math.max(best, score);
    try { localStorage.setItem('neonflap.best', String(best)); } catch (e) { /* private mode */ }

    window.PixelPayout.reportScore(score);
  }

  // --- update --------------------------------------------------------------
  function update(dt) {
    var groundY = H * 0.86;
    scrolled += PIPE_SPEED * dt;

    if (state === READY) {
      // Idle bob, so the start screen isn't a static image.
      bird.y = H * 0.44 + Math.sin(performance.now() / 320) * H * 0.012;
      bird.rot = 0;
      return;
    }

    if (state === DEAD) {
      // Let the bird fall to the ground rather than freezing mid-air.
      bird.vy += G * dt;
      bird.y = Math.min(bird.y + bird.vy * dt, groundY - BIRD_R);
      bird.rot = Math.min(bird.rot + dt * 6, Math.PI / 2);
      flashAlpha = Math.max(0, flashAlpha - dt * 3);
      return;
    }

    bird.vy += G * dt;
    bird.y += bird.vy * dt;
    bird.rot = Math.max(-0.5, Math.min(1.2, bird.vy / (H * 1.1)));

    if (bird.y + BIRD_R >= groundY || bird.y - BIRD_R <= 0) {
      bird.y = Math.min(bird.y, groundY - BIRD_R);
      die();
      return;
    }

    for (var i = 0; i < pipes.length; i++) {
      var p = pipes[i];
      p.x -= PIPE_SPEED * dt;

      var withinX = bird.x + BIRD_R > p.x && bird.x - BIRD_R < p.x + PIPE_W;
      if (withinX && (bird.y - BIRD_R < p.top || bird.y + BIRD_R > p.top + PIPE_GAP)) {
        die();
        return;
      }

      if (!p.passed && p.x + PIPE_W < bird.x - BIRD_R) {
        p.passed = true;
        score++;
        blip(880 + Math.min(score, 12) * 30, 0.09, 'triangle', 0.05);
      }
    }

    // Recycle: drop what has left the screen, keep a few queued ahead.
    while (pipes.length && pipes[0].x + PIPE_W < -10) pipes.shift();
    var last = pipes[pipes.length - 1];
    if (last && last.x < W + PIPE_SPACING * 3) spawnPipe(last.x + PIPE_SPACING);
  }

  // --- draw ----------------------------------------------------------------
  /** Stable 0..1 value for an integer. Same index, same answer, forever. */
  function hash(i) {
    var n = Math.sin(i * 12.9898) * 43758.5453;
    return n - Math.floor(n);
  }

  function roundRect(x, y, w, h, r) {
    var rr = Math.min(r, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(x + rr, y);
    ctx.arcTo(x + w, y, x + w, y + h, rr);
    ctx.arcTo(x + w, y + h, x, y + h, rr);
    ctx.arcTo(x, y + h, x, y, rr);
    ctx.arcTo(x, y, x + w, y, rr);
    ctx.closePath();
  }

  function drawBackground() {
    var groundY = H * 0.86;

    var sky = ctx.createLinearGradient(0, 0, 0, H);
    sky.addColorStop(0, '#0b1026');
    sky.addColorStop(0.55, '#141a3d');
    sky.addColorStop(1, '#2a1b47');
    ctx.fillStyle = sky;
    ctx.fillRect(0, 0, W, H);

    // Parallax skyline. Each building's height is hashed from its own index in
    // world space, not from its slot on screen: as the row scrolls, a building
    // keeps its height instead of inheriting the height of whichever slot it
    // has drifted into. Getting that backwards is what made the skyline flicker.
    var spacing = W * 0.14;
    var par = scrolled * 0.25;
    var firstIndex = Math.floor(par / spacing);

    ctx.fillStyle = 'rgba(90, 70, 160, 0.35)';
    var slots = Math.ceil(W / spacing) + 2;
    for (var k = -1; k < slots; k++) {
      var index = firstIndex + k;
      var bh = H * (0.08 + hash(index) * 0.12);
      ctx.fillRect(index * spacing - par, groundY - bh, W * 0.11, bh);
    }
  }

  function drawPipes() {
    var groundY = H * 0.86;
    for (var i = 0; i < pipes.length; i++) {
      var p = pipes[i];

      var grad = ctx.createLinearGradient(p.x, 0, p.x + PIPE_W, 0);
      grad.addColorStop(0, '#12d6a0');
      grad.addColorStop(0.5, '#31f2c0');
      grad.addColorStop(1, '#0aa47c');

      ctx.fillStyle = grad;
      ctx.shadowColor = 'rgba(49, 242, 192, 0.55)';
      ctx.shadowBlur = H * 0.02;

      roundRect(p.x, p.top - H * 0.9, PIPE_W, H * 0.9, PIPE_W * 0.22);
      ctx.fill();
      roundRect(p.x, p.top + PIPE_GAP, PIPE_W, groundY - (p.top + PIPE_GAP), PIPE_W * 0.22);
      ctx.fill();
      ctx.shadowBlur = 0;

      // Lip on each mouth - the one bit of detail that sells them as pipes.
      ctx.fillStyle = 'rgba(255,255,255,0.18)';
      roundRect(p.x - PIPE_W * 0.06, p.top - H * 0.022, PIPE_W * 1.12, H * 0.022, PIPE_W * 0.1);
      ctx.fill();
      roundRect(p.x - PIPE_W * 0.06, p.top + PIPE_GAP, PIPE_W * 1.12, H * 0.022, PIPE_W * 0.1);
      ctx.fill();
    }
  }

  function drawGround() {
    var groundY = H * 0.86;

    ctx.fillStyle = '#1b1140';
    ctx.fillRect(0, groundY, W, H - groundY);
    ctx.fillStyle = 'rgba(49, 242, 192, 0.5)';
    ctx.fillRect(0, groundY, W, H * 0.004);

    ctx.fillStyle = 'rgba(255,255,255,0.06)';
    var step = H * 0.06;
    for (var x = -(scrolled % step); x < W; x += step) {
      ctx.fillRect(x, groundY + H * 0.012, step * 0.5, H * 0.008);
    }
  }

  function drawBird() {
    ctx.save();
    ctx.translate(bird.x, bird.y);
    ctx.rotate(bird.rot);

    ctx.shadowColor = 'rgba(255, 209, 102, 0.7)';
    ctx.shadowBlur = H * 0.025;
    var body = ctx.createLinearGradient(-BIRD_R, -BIRD_R, BIRD_R, BIRD_R);
    body.addColorStop(0, '#ffe29a');
    body.addColorStop(1, '#ff9f43');
    ctx.fillStyle = body;
    ctx.beginPath();
    ctx.arc(0, 0, BIRD_R, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;

    ctx.fillStyle = 'rgba(255,255,255,0.75)';
    var wing = Math.sin(performance.now() / 60) * BIRD_R * 0.25;
    ctx.beginPath();
    ctx.ellipse(-BIRD_R * 0.15, wing, BIRD_R * 0.55, BIRD_R * 0.3, -0.3, 0, Math.PI * 2);
    ctx.fill();

    ctx.fillStyle = '#1b1140';
    ctx.beginPath();
    ctx.arc(BIRD_R * 0.42, -BIRD_R * 0.25, BIRD_R * 0.17, 0, Math.PI * 2);
    ctx.fill();

    ctx.fillStyle = '#ff6b6b';
    ctx.beginPath();
    ctx.moveTo(BIRD_R * 0.8, 0);
    ctx.lineTo(BIRD_R * 1.45, BIRD_R * 0.16);
    ctx.lineTo(BIRD_R * 0.8, BIRD_R * 0.34);
    ctx.closePath();
    ctx.fill();

    ctx.restore();
  }

  function drawText(text, y, size, color, weight) {
    ctx.fillStyle = color;
    ctx.font = (weight || '700') + ' ' + size + 'px system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(text, W / 2, y);
  }

  function drawHud() {
    if (state === PLAYING || state === DEAD) {
      ctx.save();
      ctx.shadowColor = 'rgba(0,0,0,0.6)';
      ctx.shadowBlur = 8;
      drawText(String(score), H * 0.13, H * 0.085, '#ffffff');
      ctx.restore();
    }

    if (state === READY) {
      drawText('NEON FLAP', H * 0.3, H * 0.062, '#31f2c0');
      drawText('Tap to fly', H * 0.37, H * 0.032, 'rgba(255,255,255,0.8)', '500');
      if (best) drawText('Best  ' + best, H * 0.42, H * 0.026, 'rgba(255,255,255,0.45)', '500');
    }

    // Held back a beat so the card doesn't cover the crash that caused it.
    if (state === DEAD && performance.now() - deadAt > 450) {
      var cw = Math.min(W * 0.78, H * 0.5);
      var ch = H * 0.26;
      var cx = (W - cw) / 2;
      var cy = H * 0.3;

      ctx.fillStyle = 'rgba(11, 16, 38, 0.92)';
      roundRect(cx, cy, cw, ch, H * 0.02);
      ctx.fill();
      ctx.strokeStyle = 'rgba(49, 242, 192, 0.5)';
      ctx.lineWidth = 2;
      ctx.stroke();

      drawText('GAME OVER', cy + ch * 0.22, H * 0.042, '#ff6b6b');
      drawText(String(score), cy + ch * 0.52, H * 0.075, '#ffffff');
      drawText(score >= best ? 'NEW BEST' : 'Best  ' + best, cy + ch * 0.78, H * 0.024,
        score >= best ? '#31f2c0' : 'rgba(255,255,255,0.5)', '600');
    }

    if (flashAlpha > 0) {
      ctx.fillStyle = 'rgba(255, 255, 255, ' + flashAlpha * 0.5 + ')';
      ctx.fillRect(0, 0, W, H);
    }
  }

  function draw() {
    drawBackground();
    drawPipes();
    drawGround();
    drawBird();
    drawHud();
  }

  // --- loop ----------------------------------------------------------------
  var last = 0;

  function frame(now) {
    // Clamped: a backgrounded WebView resumes with a huge delta, which would
    // otherwise teleport the bird straight through a pipe on the frame back.
    var dt = Math.min((now - last) / 1000, 0.05);
    last = now;
    update(dt);
    draw();
    requestAnimationFrame(frame);
  }

  try { best = parseInt(localStorage.getItem('neonflap.best'), 10) || 0; } catch (e) { best = 0; }

  resize();
  reset();

  window.addEventListener('resize', function () {
    resize();
    if (state === READY) reset();
  });
  window.addEventListener('orientationchange', function () { setTimeout(resize, 120); });

  canvas.addEventListener('pointerdown', function (e) { e.preventDefault(); tap(); });
  window.addEventListener('keydown', function (e) {
    if (e.code === 'Space' || e.code === 'ArrowUp') { e.preventDefault(); tap(); }
  });

  requestAnimationFrame(function (t) { last = t; requestAnimationFrame(frame); });
})();
