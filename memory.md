# Memory — TOTVS Mobile (Android / Java)

## Overview
Native **Android app in Java** (`com.tlean07.totvs`) that is a *second client* for the
existing **FIAP Challenge** system (`C:\Programming\Java\FIAP\Challenge`). It consumes the
**same** Spring Boot backend (Render), the **same** MongoDB (through the backend) and the
**same** Hugging Face AI service (through the backend). No separate backend, DB or auth was
created. The web (Next.js on Vercel) keeps working unchanged.

Min SDK 29, target/compile SDK 36, Java 17, AGP 9.1.1, Gradle 9.3.1, ViewBinding.

---

## Mobile Android Java App

### Architecture (layers)
```
com.tlean07.totvs
├── TotvsApp                      composition root / service locator
├── network                      Retrofit + OkHttp stack
│   ├── ApiClient                builds clients (main + refresh) + Retrofit
│   ├── ApiService               endpoint definitions (same as web)
│   ├── AuthInterceptor          adds Bearer + X-Request-ID + X-Device-Id (no Origin)
│   ├── TokenAuthenticator       401 → single refresh → retry; forced logout on failure
│   ├── PersistentCookieJar      stores httpOnly refresh_token cookie (encrypted)
│   ├── DataRepository / Calls   dashboard, transcrições (IA), alertas
│   ├── NetworkResult / ErrorMapper / AuthEvents
│   └── dto/                     POJOs mirroring backend DTOs
├── auth
│   ├── AuthRepository           login / silentRefresh / logout
│   ├── biometric/BiometricHelper   ported from LifeCode Kotlin ref → Java
│   └── passkey/PasskeyManager      Credential Manager scaffold (next step)
├── cas
│   ├── DeviceManager            stable device id + binding to user (local CAS)
│   └── DeviceInfo               mirrors planned mobile_devices collection
├── storage
│   ├── SecureStorage            EncryptedSharedPreferences (Android Keystore)
│   └── SessionManager           access token in memory, flags/cookie via storage
├── security/SecurityManager     FLAG_SECURE, inactivity lock, debug detection
├── util
│   ├── SafeAreaHelper           edge-to-edge WindowInsets (S25 Ultra, cutouts, gesture)
│   ├── AppExecutors / Callback / Logger (sanitised)
└── ui  SplashActivity, login/, MainActivity (bottom nav),
        dashboard/, ai/, profile/, security/, components/ (adapters)
```

### Endpoints reused (no backend change)
- `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`
- `GET /api/dashboard`
- `GET /api/transcricoes`, `GET /api/transcricoes/{id}`, `POST /api/transcricoes`
- `GET /api/alertas`, `PATCH /api/alertas/{id}/visto`
- `GET /api/users`, `POST /api/users`, `PATCH /api/users/{id}/activate|deactivate` (admin)

### Charts & admin (parity with web)
- Dashboard charts: **DonutChartView** (distribuição de sentimento + legenda) and
  **BarChartView** (riscos por tipo, oportunidades por tipo) — custom canvas views, no 3rd-party
  lib. Same colors/labels as the web `app/page.tsx` (POSITIVO/NEGATIVO/…, CHURN/…, UPSELL/…).
- **UsersActivity** (admin-only, opened from Perfil): lista de usuários, criar usuário
  (nome/email/senha≥12/role), ativar/desativar, com resumo Total/Ativos/Admins. Espelha a página
  `usuarios` da web. Botão "Gerenciar usuários" só aparece para `role == ADMIN`.
- Splash: logo passa por `splash_logo.xml` (inset) para caber no círculo da SplashScreen do
  Android 12+ (antes o wordmark era recortado).

### Endpoints NOT created (intentional, additive backend design only)
Documented for a future, retrocompatible backend PR (would not break web):
`/api/mobile/devices/register|verify|me`, `/api/mobile/devices/{id}/revoke`,
`/api/mobile/auth/passkey/challenge|verify`. Until then, server-side device control is
provided by the existing `refresh_tokens` collection (logout + refresh-reuse revocation).

### Tokens
- **Access token**: custom HS256 JWT, 15 min, kept **in memory only** (never persisted).
  Sent as `Authorization: Bearer`.
- **Refresh token**: opaque, issued by the backend as an **httpOnly cookie** (`refresh_token`,
  path `/api/auth`). Persisted by `PersistentCookieJar` inside `EncryptedSharedPreferences`
  → exact same refresh mechanism as the web client, fully reused. Rotated on every refresh.
- `TokenAuthenticator` performs a single, serialised refresh on 401; on failure it clears the
  session and raises `AuthEvents.notifySessionExpired()` → routes to Login.
- The backend `OriginValidationService` accepts requests with **no Origin** header, so the
  native client passes; we never send an Origin.

### Secure storage
- `SecureStorage` = `EncryptedSharedPreferences` (AES256-GCM value / AES256-SIV key) with a
  `MasterKey` in the Android Keystore. Holds: refresh cookie, device identity (CAS), biometric
  & secure-screen flags, cached display user. Corrupt-store self-heal on init.
- Plain SharedPreferences are never used for secrets. Logout/revoke wipe everything.
- `Logger` redacts tokens/passwords/bearer/refresh cookie; logging only in debug.

### Biometrics / passkey
- `BiometricHelper` (Java port of the Kotlin reference) uses AndroidX `BiometricPrompt` +
  `BiometricManager` (STRONG/WEAK). **Difference from reference:** no email/password is stored;
  biometrics only gate a *local* restore of the server session. Backend still validates every
  request, so a revoked device/session can't be bypassed.
- Flow: 1st login = email/password → offer to enable biometric → next cold start / resume
  after the inactivity lock requires biometric before `silentRefresh`.
- `PasskeyManager` wraps `CredentialManager` (Samsung Pass / GMS providers) as the scaffold for
  the FIDO2 passkey flow once the three backend passkey endpoints exist.

### CAS / device binding
- `DeviceManager` creates a stable, non-secret `devicePublicId` (UUID) on first run, stored
  encrypted; sent on every request as `X-Device-Id` for backend auditing. Bound to the user id
  on login (`DeviceInfo`, fields mirror the planned `mobile_devices` collection). Local revoke
  + server logout (revokes the MongoDB refresh-token session) ends access.

### UI respects status/navigation bar
- Theme draws edge-to-edge: transparent status & navigation bars,
  `WindowCompat.setDecorFitsSystemWindows(false)`. `SafeAreaHelper` re-applies dynamic padding
  from `WindowInsetsCompat` (systemBars + displayCutout + ime): top inset on every gradient
  header, bottom inset on the bottom navigation. No hardcoded bar padding → correct on S25 Ultra
  (tall/curved + cutout), gesture nav and 3-button nav.

### Visual identity reused from web
- Palette: primary `#1A365D`, secondary `#2B6CB0`, accent `#38A169`, header gradient
  `#173A64→#2868A8`, bg `#F8FAFC` / login `#EEF3F8` — same as `globals.css`.
- Logo `totvs-logo.png` copied from `frontend/public` → splash, login, dashboard headers.
- Tagline "Conversational Intelligence". Light + dark themes, Material cards/buttons, rounded
  inputs, loading/empty/error states.

### Security hardening
- HTTPS enforced via `network_security_config` (cleartext only for `10.0.2.2`/localhost debug).
- `FLAG_SECURE` on the authenticated shell (toggle in Security screen).
- Inactivity lock after 120s → biometric re-auth on resume.
- Debounced auth buttons; backend rate-limits login/refresh.
- App never decides permissions — backend validates user/token/device/role.

---

## How to run
- Backend running (local `:8080` for debug → emulator host `http://10.0.2.2:8080/api/`,
  configured in `app/build.gradle` `buildConfigField API_BASE_URL`). Release points at
  `https://challenge-totvs-backend.onrender.com/api/`.
- Open in Android Studio or CLI; `local.properties` has the SDK path.

## Build / APK
- Debug APK: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
- Release APK: `./gradlew :app:assembleRelease` (needs a signing config; not added yet).
- Unit tests: `./gradlew :app:testDebugUnitTest`. **Verified: both build green (Gradle 9.3.1).**

## Limitations / next steps
- Backend mobile/passkey endpoints not yet implemented → passkey is a scaffold; CAS revocation
  is local + via logout (refresh-token revocation), not a dedicated device API yet.
- No release signing config / certificate pinning (avoid breaking Render cert rotation).
- AI = transcription analysis (no standalone chat endpoint exists in the backend).
- On-device instrumented tests not run here (no emulator); manual test matrix in README.
