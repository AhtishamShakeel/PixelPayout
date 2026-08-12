# PixelPayout App Documentation

Last reviewed from code: 2026-06-25

This document describes the PixelPayout Android app based on the current codebase. It covers the visible product behavior, data flow, Firebase usage, points, referrals, quizzes, games, rewards, caching, and known implementation notes.

## 1. App Summary

PixelPayout is an Android rewards app where users can:

- Create an account or log in.
- Earn points/stars from quizzes.
- Earn points from hosted web games.
- Use referral codes.
- Let referrers earn delayed referral rewards.
- Open a Tapjoy offerwall.
- Navigate to a cashout/redemption section.

The app uses:

- Firebase Auth for email/password and Google sign-in.
- Firestore for user documents, points, referral state, quiz attempts, and profile metadata.
- Firebase Functions for email lookup, daily attempt reset, referral submission, and delayed referrer rewards.
- Firebase Hosting for remote quiz JSON and hosted games.
- DataStore for local username/referral-popup preferences.
- Internal app storage for cached quiz JSON.
- AdMob for rewarded/banner ads.
- Tapjoy for offerwall rewards.

## 2. Package And App Startup

Application id:

```text
com.pixelpayout
```

Android namespace:

```text
com.pixelpayout
```

Main application class:

```text
com.pixelpayout.PixelPayoutApp
```

Startup behavior:

1. Android launches `PixelPayoutApp`.
2. `PixelPayoutApp.onCreate()` initializes Firebase.
3. It initializes Mobile Ads SDK.
4. It forces night mode with `AppCompatDelegate.MODE_NIGHT_YES`.
5. Launcher activity is `OnboardingActivity`.

Manifest permissions:

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- Google Ad ID permission is explicitly removed with `tools:node="remove"`.

Registered activities:

- `OnboardingActivity` as launcher.
- `Auth` for login/signup.
- `MainActivity` for main app shell.
- `QuizActivity` for quiz attempts.
- `GamePlayActivity` for WebView games.
- Tapjoy activities:
  - `TJAdUnitActivity`
  - `TJWebViewActivity`

## 3. Main Navigation

The main navigation graph starts at Home.

Bottom nav sections:

- Home
- Play Game
- Quizzes
- Rewards
- Cashout

Navigation destinations:

- `HomeFragment`
- `GameFragment`
- `QuizListFragment`
- `RewardsFragment`
- `RedemptionFragment`
- `DetailsFragment`

`MainActivity` owns:

- Bottom navigation.
- Custom toolbar.
- Points display.
- Username greeting.
- Internet connectivity dialog.
- Quiz loading/retry dialog.
- Referral popup check.
- Lottie animation preloading for quiz categories.

## 4. Onboarding

Launcher screen:

```text
OnboardingActivity
```

On startup:

1. `OnboardingViewModel` initializes Firebase defensively.
2. It checks whether a Firebase user is already logged in.
3. If logged in, app opens `MainActivity`.
4. If not logged in, onboarding slides are shown.

Onboarding slides:

- Welcome to PixelPayout
- Daily Rewards
- Cash Out

The slides auto-scroll every 5 seconds.

The onboarding screen also shows terms/privacy text. Tapping "Terms and Conditions" or "Privacy Policy" opens `TermsDialogFragment`.

Primary action:

- "Get Started" opens `Auth` activity.

## 5. Authentication

Auth screen:

```text
Auth
```

Supported methods:

- Email/password login.
- Email/password signup.
- Google sign-in.

### 5.1 Email Detection Flow

The user first enters an email and taps Continue.

Flow:

1. Email format is validated locally.
2. `AuthViewModel.checkIfEmailExists(email)` calls Firebase Function `checkEmailExists`.
3. Function uses Firebase Admin Auth to check whether the email exists.
4. App shows either:
   - Login form if email exists.
   - Signup form if email does not exist.

### 5.2 Email Login Flow

When logging in:

1. User enters email and password.
2. `AuthViewModel.login()` calls `FirebaseAuth.signInWithEmailAndPassword`.
3. Login has a 10 second timeout.
4. On success:
   - Firestore user document is loaded.
   - `displayName` is saved to local DataStore.
   - User enters `MainActivity`.
5. On failure:
   - Login button resets.
   - Error toast is shown.

### 5.3 Email Signup Flow

When signing up:

Required fields:

- Name.
- Email.
- Password.
- Confirm password.

Validation:

- Name must not be blank.
- Password must be at least 6 characters.
- Password and confirm password must match.

Signup flow:

1. Android ID is read from `Settings.Secure.ANDROID_ID`.
2. `FirebaseAuth.createUserWithEmailAndPassword` creates the auth user.
3. Firestore checks if another user already has the same `androidId`.
4. `hasUsedReferral` is set to true if this Android ID was already used before.
5. Firestore user document is created.
6. Local preferences are updated:
   - username
   - hasSeenReferralPopup = false
7. User enters `MainActivity`.

Email signup Firestore user fields:

```text
displayName
email
androidId
hasUsedReferral
joinedDate
lastActive
lastServerDate
points
referralCode
referralRewardClaimed
```

Security note:

- Password is no longer stored in Firestore.
- Firebase Auth owns password handling.

### 5.4 Google Sign-In Flow

Google sign-in uses:

- `GoogleSignInOptions.DEFAULT_SIGN_IN`
- `requestIdToken(default_web_client_id)`
- `requestEmail()`

Flow:

1. User taps Google sign-in.
2. Google sign-in UI opens.
3. App receives ID token.
4. Firebase signs in with `GoogleAuthProvider`.
5. App gets Android ID.
6. Local username is saved.
7. `AuthViewModel.checkIfUserExists()` checks Firestore.
8. If user exists:
   - app logs in.
9. If user does not exist:
   - `createNewUser()` creates Firestore user document.

Google signup Firestore user fields:

```text
uid
displayName
email
androidId
hasUsedReferral
joinedDate
lastActive
lastServerDate
points
referralCode
referralRewardClaimed
```

## 6. Local Preferences

Stored with DataStore:

```text
username
hasSeenReferralPopup
```

Usage:

- `username` powers toolbar greeting: `Hey, User`.
- `hasSeenReferralPopup` prevents repeatedly showing the referral popup.

## 7. Main Screen And Toolbar

`MainActivity`:

- Sets night mode.
- Sets up connectivity monitoring.
- Loads cached quizzes.
- Refreshes quiz attempt state.
- Preloads Lottie animations for quiz categories.
- Observes points from `MainViewModel`.
- Shows current points in toolbar.
- Clicking the points header navigates to Cashout/Redemption.

Points are observed through:

```text
MainViewModel -> UserRepository.userData -> Firestore snapshot listener
```

## 8. Connectivity Handling

Connectivity class:

```text
AndroidConnectivityCheck
```

It exposes:

```text
Flow<Boolean> isConnected
```

How it works:

- Uses Android `ConnectivityManager`.
- Checks `NET_CAPABILITY_VALIDATED`.
- For slower/non-Wi-Fi networks, performs an extra HTTP check against:

```text
https://clients3.google.com/generate_204
```

If internet is lost:

- MainActivity shows `NoInternetDialog`.
- QuizActivity and GamePlayActivity call `MainActivity.handleInternetDisconnection()`, finish themselves, and return to MainActivity.

Known note:

- `AndroidConnectivityCheck` uses `GlobalScope` inside network callback. This works but is not ideal lifecycle-wise.

## 9. UserRepository And Points

Main repository:

```text
UserRepository
```

Responsibilities:

- Tracks current Firebase Auth user.
- Watches Firestore user document.
- Emits user points via LiveData.
- Updates user points.
- Updates quiz attempts.
- Calls referral Cloud Function.

Realtime points:

1. `waitForUserLogin()` attaches an auth state listener.
2. When a user exists, `setupRealtimeUpdates(userId)` attaches a Firestore snapshot listener.
3. It reads `points`.
4. It posts `UserData(points)`.

Point update functions:

```text
updateUserPoints(pointsToAdd)
updateUserPointsAndAttempts(pointsToAdd)
```

`updateUserPoints`:

- Used by games.
- Runs a Firestore transaction.
- Reads current points.
- Increments points by `pointsToAdd`.
- Returns new total through callback.

`updateUserPointsAndAttempts`:

- Used by quizzes.
- Runs a Firestore transaction.
- Reads current points.
- Increments points by `pointsToAdd`.
- Increments `quiz_attempts` by 1.
- Returns new total through callback.

Important current security note:

- Quiz and game point writes are still client-driven.
- Referral submission and delayed referrer reward are now server-side.
- A future `claimReward` Cloud Function would make quiz/game rewards more secure.

## 10. Referral System

Referral behavior:

- Every new user gets a 6-character referral code.
- Characters are uppercase A-Z and digits 1-9/0.
- A user can submit another user's referral code.
- The referred user receives 50 points immediately.
- The referrer receives 100 points only after the referred user reaches 100 points.
- Referrer reward is only paid once.

### 10.1 Referral Popup

MainActivity checks referral popup state:

1. Reads `hasSeenReferralPopup` from DataStore.
2. If already seen, does nothing.
3. If not seen, sets it to true.
4. Reads Firestore user document.
5. If `hasUsedReferral` is false, shows `ReferralDialogFragment`.

Referral dialog:

- Cannot be dismissed by tapping outside.
- Has a close button.
- Submits referral code through `ReferralViewModel`.

### 10.2 Referral In Redemption Screen

`RedemptionFragment` also includes referral code input.

It:

1. Reads referral code from text field.
2. Validates non-empty.
3. Calls `ReferralViewModel.submitReferral(referralCode)`.
4. Shows Snackbar or field errors.

### 10.3 Server-Side Referral Submission

Android calls:

```text
submitReferral
```

Cloud Function:

```text
submitReferral
```

Server behavior:

1. Requires authenticated user.
2. Reads `referralCode`.
3. Trims and uppercases it.
4. Finds user with matching `referralCode`.
5. Rejects invalid code.
6. Rejects self-referral.
7. Reads current user document in Firestore transaction.
8. Rejects if `hasUsedReferral` is already true.
9. Updates current user:
   - `hasUsedReferral = true`
   - `referredBy = referrerId`
   - increments `points` by 50
10. Returns status.

Android maps response:

- `success` -> `ReferralResult.Success`
- `invalid_code` -> `ReferralResult.InvalidCode`
- failed-precondition -> `ReferralResult.AlreadyUsed`
- other errors -> `ReferralResult.Error`

### 10.4 Delayed Referrer Reward

Cloud Function:

```text
rewardReferrerOnPointsThreshold
```

Trigger:

```text
onDocumentUpdated("users/{userId}")
```

Server behavior:

1. Runs whenever a user document updates.
2. Reads before/after points.
3. Reads:
   - `referredBy`
   - `referralRewardClaimed`
4. Skips if:
   - user was already at or above 100 points before update.
   - user is still below 100 points.
   - `referredBy` is missing.
   - `referralRewardClaimed` is true.
5. In transaction:
   - rechecks `referralRewardClaimed`.
   - increments referrer's points by 100.
   - sets referred user's `referralRewardClaimed = true`.

This was tested manually:

- New user got 50 immediately.
- Referrer got 100 when new user reached 100.
- Reducing and re-raising referred user's points did not pay reward again.

## 11. Quizzes

Quiz section:

```text
QuizListFragment
```

ViewModel:

```text
QuizListViewModel
```

Quiz play screen:

```text
QuizActivity
```

Quiz result logic:

```text
QuizViewModel
```

### 11.1 Quiz Categories

Default quiz categories:

- Animals
- Sports
- Science
- Riddles
- Geography
- Math Fun
- Video Games
- GK

Each category maps to a Lottie animation resource.

If remote category is unknown, it uses default quiz animation.

### 11.2 Quiz Data Source

Remote quiz JSON:

```text
https://quizzes-b446b.web.app/quizzes.json
```

Data model:

```text
QuizData
  version
  categories

Category
  name
  quizzes

Quiz
  id
  title
  difficulty
  pointsReward
  questions

Question
  text
  options
  correctAnswer
```

### 11.3 Quiz Cache

Cache manager:

```text
QuizDataManager
```

Local cache file:

```text
quizzes.json
```

Stored in app internal files directory.

SharedPreferences:

```text
quiz_cache
```

Keys:

- `version`
- `last_update_check`

Update check interval:

```text
24 hours
```

Quiz cache behavior:

1. App loads cached quizzes immediately.
2. App checks Firebase Hosting for newer quiz JSON only if:
   - there is no cache, or
   - last update check was more than 24 hours ago.
3. If remote version is newer:
   - saves JSON to cache.
   - saves new version.
   - reloads quizzes.
4. If remote version is same:
   - keeps cached data.
5. If remote fails:
   - keeps cached data.

### 11.4 Daily Quiz Attempts

Maximum daily attempts:

```text
10
```

Attempt state is fetched from Cloud Function:

```text
checkAndResetQuizAttempts
```

The function:

1. Requires authenticated user.
2. Loads user's Firestore document.
3. Uses server time.
4. Checks if UTC date changed since `last_reset_time`.
5. If new day:
   - sets `quiz_attempts = 0`
   - sets `last_reset_time = now`
   - returns attempts 0.
6. If same day:
   - returns existing `quiz_attempts`.

App-side behavior:

- `QuizListViewModel.fetchDailyAttempts()`
- Shows loading dialog while checking.
- On success:
  - updates daily attempts.
  - updates last reset time.
  - computes next reset time.
  - hides loading dialog.
- On failure:
  - shows retry button in loading dialog.

Refresh throttling:

- Attempts are not refetched more often than every 5 minutes unless forced.
- Forced refresh is used after quiz completion and retry.

### 11.5 Quiz Selection

When user taps category:

1. App checks attempts.
2. If attempts >= 10:
   - shows daily limit toast.
3. If attempts remain:
   - filters loaded quizzes by category title.
   - filters valid quizzes with non-empty questions and text.
   - randomly selects one quiz.
   - randomly selects one question from that quiz.
   - opens `QuizActivity` with only that question.

### 11.6 Quiz Play

QuizActivity:

- Shows question text.
- HTML-decodes question text.
- Shows answer options.
- User selects one option.
- Submit button is disabled after submit.
- Correct answer is highlighted green.
- Wrong selected answer is highlighted red.
- Timer is 30 seconds.
- If timer finishes, answer is submitted as wrong.

### 11.7 Quiz Points

In `QuizViewModel`:

- If selected answer index equals `correctAnswer`, points increase by `quiz.pointsReward`.
- If answer is wrong or timed out, points remain 0.
- `UserRepository.updateUserPointsAndAttempts(points)` updates Firestore:
  - increments points by earned amount.
  - increments quiz attempts by 1.

Known note:

- Quiz point awarding is still client-side.
- It should eventually move to a server-side `claimReward` callable for better anti-cheat.

## 12. Games

Game section:

```text
GameFragment
```

Available hosted games:

- 2048:
  - `https://game-ccdff.web.app/`
- Flappy Bird:
  - `https://floppybird-bc843.web.app/`

### 12.1 Game Launch

When user taps game:

1. GameFragment opens `GamePlayActivity`.
2. It passes game URL in intent extra:

```text
GAME_URL
```

### 12.2 Game WebView

GamePlayActivity:

- Creates AdMob banner.
- Loads hosted game in WebView.
- Enables JavaScript.
- Enables DOM storage.
- Enables file access.
- Disables zoom controls.
- Uses `LOAD_CACHE_ELSE_NETWORK`.
- Injects CSS after page load to fit the game.
- Adds JavaScript interface:

```text
AndroidInterface
```

JavaScript interface:

```kotlin
onGameComplete(points: Int)
```

Hosted game calls:

```text
AndroidInterface.onGameComplete(points)
```

Then:

1. Android calls `GamePlayViewModel.updateGamePoints(points)`.
2. ViewModel calls `UserRepository.updateUserPoints(points)`.
3. Firestore increments current user's points.
4. Activity finishes on success.

Known security note:

- Game points are still client/WebView controlled.
- Hosted game can tell the app how many points to award.
- This should eventually move to a server-side game session or `claimReward` function.

Known WebView security note:

- SSL errors currently call `handler?.proceed()`.
- This bypasses SSL protection and should be fixed before release.

## 13. Rewards And Offerwall

Rewards screen:

```text
RewardsFragment
```

Offerwall provider:

```text
Tapjoy
```

Tapjoy setup:

1. Gets current Firebase user ID through `UserRepository.getCurrentUserId()`.
2. Initializes Tapjoy with:
   - SDK key from `AppConfig.TAPJOY_SDK_KEY`
   - `TapjoyConnectFlag.USER_ID = userId`
   - logging disabled
3. Creates `TJPlacement` using placement:

```text
offerwall
```

Offerwall button:

- If content available, shows offerwall.
- Otherwise logs that content is unavailable.

Current reward handling:

- `onRewardRequest()` logs reward amount/currency.
- The code does not currently award points from Tapjoy callback in the app.

## 14. Ads

Ad config:

```text
AppConfig
```

AdMob app ID:

```text
@string/admob_app_id
```

Ad units:

- Rewarded ad:
  - `ADMOB_REWARDED_AD_UNIT_ID`
- Game banner:
  - `ADMOB_GAME_BANNER_AD_UNIT_ID`

GamePlayActivity:

- Shows banner ad at bottom/inside `adContainer`.

AdManager:

- Singleton.
- Loads rewarded ad.
- Tracks if ad is already loading.
- Exposes ad availability callback.
- Shows rewarded ad and calls:
  - `onRewarded`
  - `onAdClosed`
  - `onAdFailedToShow`

Current note:

- Rewarded ad support exists in code but current visible quiz/game flow does not clearly use it for extra attempts.

## 15. Redemption And Cashout

Cashout/redemption screen:

```text
RedemptionFragment
```

Current implementation:

- Contains referral code submission.
- Uses `ReferralViewModel`.
- Shows success/error via Snackbar.

Redemption model exists:

```text
RedemptionOption
```

Fields:

- id
- title
- description
- pointsCost
- type
- imageUrl

Redemption types:

- EASYPAISA
- GAME_CURRENCY

Current note:

- The data model exists, and strings exist for phone/game ID/redemption success/failure.
- The current `RedemptionFragment` code shown is focused on referral submission.
- Actual cashout request submission logic is not implemented in the inspected fragment.

## 16. Details Screen

DetailsFragment supports:

- quiz details
- game details

Quiz details:

- Shows quiz rules.
- Rewards text says:
  - Easy: 10 stars
  - Medium: 20 stars
  - Hard: 30 stars

Game details:

- Shows game rules.
- Rewards text says:
  - 5-10 stars per minute based on performance

Note:

- These are static UI descriptions.
- Actual quiz reward comes from `quiz.pointsReward`.
- Actual game reward comes from hosted game calling Android with points.

## 17. Firebase Functions

Functions file:

```text
functions/src/index.ts
```

### 17.1 weeklyReset

Schedule:

```text
every monday 00:00
```

Behavior:

- Reads all users.
- Sets:
  - `quiz_attempts = 0`
  - `last_reset_time = now`

Note:

- Comment says it is a weekly safety net backup.

### 17.2 checkEmailExists

Callable function.

Input:

```text
email
```

Behavior:

- Uses Admin Auth `getUserByEmail`.
- Returns:
  - `{ exists: true }`
  - `{ exists: false }`

Used by:

```text
AuthViewModel.checkIfEmailExists
```

### 17.3 checkAndResetQuizAttempts

Callable function.

Requires auth.

Behavior:

- Loads user document.
- Compares `last_reset_time` with current UTC date.
- Resets attempts if day changed.
- Returns:
  - success
  - attempts
  - resetPerformed
  - lastResetTime
  - serverTime

Used by:

```text
QuizListViewModel.fetchDailyAttempts
```

### 17.4 submitReferral

Callable function.

Requires auth.

Behavior:

- Validates referral code.
- Rejects invalid/self referral.
- Rejects already-used referral.
- Sets `hasUsedReferral`.
- Sets `referredBy`.
- Awards referred user 50 points.

Used by:

```text
UserRepository.submitReferral
```

### 17.5 rewardReferrerOnPointsThreshold

Firestore document update trigger.

Watches:

```text
users/{userId}
```

Behavior:

- Detects when referred user crosses 100 points for first time.
- Rewards referrer 100 points.
- Marks referred user `referralRewardClaimed = true`.
- Logs all skip/apply decisions.

## 18. Firestore User Document

The app expects user documents under:

```text
users/{uid}
```

Known fields:

```text
uid
displayName
email
androidId
hasUsedReferral
joinedDate
lastActive
lastServerDate
last_reset_time
points
quiz_attempts
referralCode
referredBy
referralRewardClaimed
```

Not every field is set in every creation path:

- Email signup does not store `uid` in `userData`.
- Google signup stores `uid`.
- `quiz_attempts` may not exist until quiz activity/functions update it.
- `last_reset_time` may not exist until reset function runs.
- `referredBy` exists only after referral code is used.

## 19. Important Current Reward Values

Referral:

- Referred user immediate reward: 50 points.
- Referrer delayed reward: 100 points.
- Threshold for delayed reward: 100 points.

Quiz:

- Max attempts: 10 per day.
- Quiz reward amount comes from remote quiz JSON field:

```text
pointsReward
```

Game:

- Game reward amount is currently sent from hosted game JavaScript.

## 20. Current Known Risks And Technical Debt

### 20.1 Client-Side Quiz/Game Rewards

Quiz and game points are still awarded through Android Firestore writes.

Risk:

- A modified client could call point update methods with arbitrary points.

Recommended future fix:

- Create one callable function such as `claimReward`.
- Server validates quiz/game reward rules.
- Android calls server instead of writing points directly.

### 20.2 Game WebView Trust

Games are hosted HTML pages.

Current trust path:

```text
Hosted game -> AndroidInterface.onGameComplete(points) -> Android -> Firestore
```

Risk:

- Hosted game or injected JS can submit arbitrary points.

Recommended future fix:

- Server-created game sessions.
- Session ID required for reward claim.
- Reward capped by game ID.
- One claim per session.
- Minimum play duration/cooldown.

### 20.3 WebView SSL Bypass

`GamePlayActivity.onReceivedSslError()` calls:

```text
handler?.proceed()
```

Risk:

- App ignores SSL errors.

Recommended fix:

- Cancel on SSL errors for production.

### 20.4 Firestore Rules Not In Repo

The repository does not include Firestore security rules.

Risk:

- Client write security cannot be audited from codebase.

Recommended fix:

- Add `firestore.rules`.
- Lock down point writes.
- Allow only server functions for sensitive reward changes.

### 20.5 Referral Code Collisions

Referral codes are random 6-character strings.

Current code does not check if generated code already exists.

Risk:

- Rare collision could assign duplicate referral code.

Recommended fix:

- Generate referral code server-side or check uniqueness before saving.

### 20.6 Device-Based Referral Blocking

Signup sets `hasUsedReferral` to true if another user has same Android ID.

Benefit:

- Blocks repeat referral use on same device.

Limitations:

- Android ID can change under some circumstances.
- Users can use multiple devices.

### 20.7 Multiple UserRepository Instances

Several screens create their own `UserRepository`.

Risk:

- Multiple Firestore listeners.
- UI state may update at different times.

Recommended fix:

- Use shared repository or dependency injection.

### 20.8 Connectivity Uses GlobalScope

`AndroidConnectivityCheck` uses `GlobalScope.launch`.

Risk:

- Work may outlive lifecycle.

Recommended fix:

- Use structured coroutine scope.

### 20.9 Firebase Functions SDK Warning

Deploy output warned that `firebase-functions` is outdated.

Current action:

- Do not upgrade during reward stabilization.

Future action:

- Upgrade carefully in a separate dependency update.

## 21. How To Change Reward Amounts Today

Referral:

- Change constants in `functions/src/index.ts`:
  - `REFERRED_USER_REWARD_POINTS`
  - `REFERRER_REWARD_POINTS`
  - `REFERRAL_REWARD_UNLOCK_POINTS`
- Redeploy functions.

Quiz:

- Change `pointsReward` in remote `quizzes.json`.
- Increment quiz JSON `version`.
- Users receive update after cache refresh window, currently 24 hours, or after cache reset/force refresh.

Game:

- Change hosted game JavaScript reward logic.
- Current Android accepts points sent by game.

Recommended future:

- Move quiz/game reward values to server config or `claimReward` function.

## 22. Suggested Next Development Steps

1. Commit current referral function changes if not already committed.
2. Add Firestore rules to repo.
3. Fix WebView SSL handling.
4. Move quiz rewards to a callable function.
5. Add server-controlled game sessions.
6. Create central reward config.
7. Clean up duplicate repository/listener creation.
8. Add referral code uniqueness.
9. Build actual redemption request submission flow.
10. Add tests/emulator scripts for Functions.

## 23. Quick File Reference

Startup:

- `app/src/main/java/com/pixelpayout/PixelPayoutApp.kt`
- `app/src/main/AndroidManifest.xml`

Auth:

- `ui/auth/Auth.kt`
- `ui/auth/AuthViewModel.kt`
- `ui/onboarding/OnboardingActivity.kt`
- `ui/onboarding/OnboardingViewModel.kt`

Main:

- `ui/main/MainActivity.kt`
- `ui/main/MainViewModel.kt`
- `ui/home/HomeFragment.kt`

Quizzes:

- `ui/quiz/QuizListFragment.kt`
- `ui/quiz/QuizListViewModel.kt`
- `ui/quiz/QuizActivity.kt`
- `ui/quiz/QuizViewModel.kt`
- `utils/QuizDataManager.kt`

Games:

- `ui/game/GameFragment.kt`
- `ui/game/GamePlayActivity.kt`
- `ui/game/GamePlayViewModel.kt`
- `ui/game/GameJavaScriptInterface.kt`

Referral/redemption:

- `data/repository/UserRepository.kt`
- `ui/redemption/RedemptionFragment.kt`
- `ui/redemption/ReferralViewModel.kt`
- `ui/dialogs/ReferralDialogFragment.kt`

Rewards/ads:

- `ui/rewards/RewardsFragment.kt`
- `utils/AdManager.kt`
- `config/AppConfig.kt`

Cloud Functions:

- `functions/src/index.ts`

