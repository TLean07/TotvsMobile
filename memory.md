# Memory — TOTVS Mobile (Android / Java)

## Overview
Native **Android app in Java** (`com.tlean07.totvs`) that is a *second client* for the
existing **FIAP Challenge** system (`C:\Programming\Java\FIAP\Challenge`). It consumes the
**same** Spring Boot backend (Render), the **same** MongoDB (through the backend) and the
**same** Hugging Face AI service (through the backend). No separate backend, DB or auth was
created. The web (Next.js on Vercel) keeps working unchanged.

Min SDK 29, target/compile SDK 36, Java 17, AGP 9.1.1, Gradle 9.3.1, ViewBinding.
Verified building/installing on a real **Samsung Galaxy S25 Ultra (Android 16 / API 36)** via adb.

---

## Mobile Android Java App

### Architecture (layers)
```
com.tlean07.totvs
├── TotvsApp                      composition root / service locator (+ CrashReporter install)
├── network                      Retrofit + OkHttp stack
│   ├── ApiClient                builds clients (main + refresh) + Retrofit (40s connect for Render cold start)
│   ├── ApiService               endpoint definitions (same as web)
│   ├── AuthInterceptor          adds Bearer + X-Request-ID + X-Device-Id (no Origin)
│   ├── TokenAuthenticator       401 → single refresh → retry; skips /auth/*; forced logout on failure
│   ├── PersistentCookieJar      stores httpOnly refresh_token cookie (encrypted)
│   ├── DataRepository / Calls   dashboard, transcrições (paged + insights + JSON import), alertas, users
│   ├── NetworkResult / ErrorMapper / AuthEvents
│   └── dto/                     POJOs mirroring backend DTOs (see below)
├── auth
│   ├── AuthRepository           login / silentRefresh / logout
│   ├── biometric/BiometricHelper   ported from LifeCode Kotlin ref → Java (crash-hardened)
│   └── passkey/PasskeyManager      Credential Manager scaffold (guarded init)
├── cas
│   ├── DeviceManager            stable device id + binding to user (local CAS)
│   └── DeviceInfo               mirrors planned mobile_devices collection
├── storage
│   ├── SecureStorage            EncryptedSharedPreferences (Android Keystore)
│   └── SessionManager           access token in memory, flags/cookie via storage
├── security/SecurityManager     FLAG_SECURE, inactivity lock, debug detection
├── util
│   ├── SafeAreaHelper           edge-to-edge WindowInsets (S25 Ultra, cutouts, gesture)
│   ├── CrashReporter            writes uncaught stack traces to a file on device
│   ├── AppExecutors / Callback / Logger (sanitised)
└── ui
    ├── SplashActivity, login/LoginActivity, MainActivity (bottom nav)
    ├── dashboard/                stats + custom charts (Donut/Bar) + recent alerts
    ├── ai/AiFragment             paginated analyses + "Nova análise" bottom sheet (text + TOTVS JSON import)
    ├── insights/                 BaseInsightsActivity + RiscosActivity + OportunidadesActivity (+ Adapter/Mapper/CardItem)
    ├── alertas/AlertasActivity   full alerts screen (filters + mark-as-seen)
    ├── profile/, security/, users/UsersActivity (admin)
    └── components/               StatAdapter, AlertaAdapter, TranscricaoAdapter, DonutChartView, BarChartView, ChartEntry
```

DTOs: AuthResponse, AuthUser, LoginRequest, ApiError, DashboardResponse (+ riscoPorTipo /
oportunidadePorTipo maps), TranscricaoResponse, TranscricaoRequest, **PageResponse**,
**TranscricaoInsightsResponse** (+ RiscoDto/RiscoConsolidadoDto/OportunidadeDto/OportunidadeConsolidadaDto),
**TranscricaoImportResponse**, AlertaResponse, **UserDto**, **CreateUserRequest**.

### Endpoints reused (no backend change)
- `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`
- `GET /api/dashboard`
- `GET /api/transcricoes`, `GET /api/transcricoes/paged?page&size`, `GET /api/transcricoes/{id}`
- `POST /api/transcricoes` (create+analyse), `POST /api/transcricoes/import/totvs` (multipart JSON)
- `GET /api/transcricoes/insights` (riscos/oportunidades, cached 5 min client-side)
- `GET /api/alertas`, `GET /api/alertas/novos`, `PATCH /api/alertas/{id}/visto`
- `GET /api/users`, `POST /api/users`, `PATCH /api/users/{id}/activate|deactivate` (admin)

### Screens & features (parity with web)
- **Splash** → silent refresh / biometric gate. Logo passes through `splash_logo.xml` (inset) so
  the wordmark fits the circular Android-12+ SplashScreen mask (was being clipped).
- **Login** — gradient background + floating `MaterialCardView`, hero title/subtitle, Material
  `TextInputLayout`s with email/lock icons + password toggle, optional biometric quick-unlock.
- **Dashboard** — 6 stat cards (Transcrições, Processadas, Oportunidades, Riscos, Alertas novos,
  **Churn**), custom charts (Donut = sentimento + legend; Bar = riscos/oportunidades por tipo —
  no 3rd-party lib, same colors/labels as web), recent alerts. Stat cards Oportunidades/Riscos/
  Churn/Alertas are tappable → open the matching screen.
- **Riscos / Oportunidades** (`insights/`) — full screens off `GET /transcricoes/insights`,
  filter chips (tipo + severidade / tipo), summary stats, detail dialog. Shared `BaseInsightsActivity`.
- **Alertas** (`AlertasActivity`) — full screen reusing the insights layout, filters by status
  (Novo/Visto/Resolvido) + prioridade (Alto/Médio/Baixo), detail dialog with "Marcar como visto".
- **Análises (AiFragment)** — infinite-scroll pagination; "Nova análise" is a **BottomSheet** with
  Material inputs **and TOTVS JSON import** (file picker → `POST /transcricoes/import/totvs`).
- **Usuários** (admin, from Perfil) — list + summary, "Novo usuário" **BottomSheet** (Material
  inputs + role dropdown), ativar/desativar. Button only visible for `role == ADMIN`.
- **Perfil / Segurança** — user + device (CAS) info, logout, biometric & secure-screen toggles,
  device revoke.

### Dark mode (real DayNight support)
- App theme is `DayNight`; the key piece is **`values-night/colors.xml`**, which redefines the
  `*_light` tokens (referenced directly by layouts) to a dark navy palette → every screen flips
  without touching layouts. Brand colors (navy gradients, accent green, chart hues) stay in both.
- Adaptive component tokens (light + night): `button_primary`, `action_fg`/`action_stroke`,
  `login_card`/`login_card_stroke`, `chart_track`/`chart_value`/`chart_label`, and chips
  `chip_role/success/muted/warning/danger` (`_bg`/`_fg`) with `bg_pill_*` drawables.
- Theme-aware chips applied to: user list (Administrador/Usuário, Ativo/Inativo) and the
  **Análises status pill** (PROCESSADA→green, PROCESSANDO→blue, PENDENTE→amber, ERRO→red, theme-aware).
- `DonutChartView`/`BarChartView` read DayNight colors. Status-bar icons forced white globally
  (top of every screen is the navy gradient header).

### Tokens
- **Access token**: custom HS256 JWT, 15 min, kept **in memory only**. Sent as `Authorization: Bearer`.
- **Refresh token**: opaque httpOnly cookie (`refresh_token`, path `/api/auth`), persisted by
  `PersistentCookieJar` inside `EncryptedSharedPreferences` → exact same refresh mechanism as web.
- `TokenAuthenticator`: single serialised refresh on 401, **skips `/auth/*` paths** (so a wrong
  login surfaces as 401 instead of triggering a refresh); on failure clears session + `AuthEvents`.
- Backend `OriginValidationService` accepts requests with **no Origin** → native client passes.

### Secure storage / hardening
- `SecureStorage` = `EncryptedSharedPreferences` (AES256-GCM/SIV, Keystore `MasterKey`), self-heal
  on corrupt store. Holds refresh cookie, device identity, biometric/secure-screen flags, cached user.
- `Logger` redacts tokens/passwords/bearer/cookie; logs only in debug.
- HTTPS enforced via `network_security_config` (cleartext only for `10.0.2.2`/localhost debug).
- `FLAG_SECURE` on the authenticated shell (toggle in Segurança → "Proteger telas sensíveis";
  turning it off is the supported way to take screenshots, then re-enable).
- Inactivity lock after 120s → biometric re-auth on resume.

### Biometrics / CAS
- `BiometricHelper` (Java, AndroidX BiometricPrompt STRONG/WEAK). No credentials stored; biometrics
  only gate a *local* session restore. **Crash-hardened**: try/catch around `canAuthenticate` and
  `authenticate`, isFinishing/isDestroyed guard, falls back to password instead of crashing.
- `DeviceManager` issues a stable non-secret `devicePublicId` (UUID, encrypted), sent as
  `X-Device-Id` for auditing; bound to the user on login. Revoke = local + server logout.

### Crash resilience (added after on-device testing)
- **`CrashReporter`** (installed first in `TotvsApp.onCreate`) writes the full stack trace of any
  uncaught exception to `Android/data/com.tlean07.totvs/files/last_crash.txt` (no adb needed).
- Fragment async callbacks (Dashboard/AI/Alertas/etc.) guard `binding == null` before touching
  views — fixed a real NPE crash on the S25 Ultra where a dashboard network result arrived after
  the view was destroyed (tab switch / biometric unlock).
- Profile/Security navigation callbacks guard `isAdded()`. PasskeyManager init wrapped in try/catch.

### Notable fixes
- **Churn card**: was using the backend `churnsPotenciais` (never populated → always 0). Now uses
  `riscoPorTipo["CHURN"]` (the real count, e.g. 133, same as the chart); card is tappable → Riscos.
- **Backend URL**: debug build defaults to the Render backend
  (`https://challenge-totvs-backend.onrender.com/api/`) so it works out of the box; connect timeout 40s
  for Render free-tier cold starts. (Swap to `http://10.0.2.2:8080/api/` for a local backend.)

### Endpoints NOT created (intentional, additive backend design only)
`/api/mobile/devices/register|verify|me`, `/api/mobile/devices/{id}/revoke`,
`/api/mobile/auth/passkey/challenge|verify` — documented for a future retrocompatible PR. Until
then, server-side device control = the existing `refresh_tokens` collection (logout + reuse revocation).

### Visual identity reused from web
Palette: primary `#1A365D`, secondary `#2B6CB0`, accent `#38A169`, header gradient `#173A64→#2868A8`.
Logo `totvs-logo.png` from `frontend/public`. Tagline "Conversational Intelligence".

---

## How to run
- Backend reachable. Debug build → Render by default (override to `10.0.2.2:8080` for local).
- Android Studio (▶) or CLI. `local.properties` has the SDK path. Tested via
  `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Build / APK
- Debug APK: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
- Release APK: `./gradlew :app:assembleRelease` (needs signing config; not added yet).
- Unit tests: `./gradlew :app:testDebugUnitTest`. Build verified green (Gradle 9.3.1).

## Limitations / next steps
- Backend mobile/passkey endpoints not yet implemented → passkey is a scaffold; CAS revocation is
  local + via logout, not a dedicated device API yet.
- No release signing config / certificate pinning.
- AI = transcription analysis (no standalone chat endpoint in the backend).
- Audio import (web VoiceRecorder) not ported; only text + TOTVS JSON import on mobile.
