# Memory — TOTVS Mobile (Android / Java)

## Overview
Native **Android app in Java** (`com.tlean07.totvs`) that is a *second client* for the
existing **FIAP Challenge** system (`C:\Programming\Java\FIAP\Challenge`). It consumes the
**same** Spring Boot backend (Render), the **same** Oracle Database (FIAP, through the backend) and the
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
- Audio: see "Gravador de reunião" below (recording, diarization and ASR are on-device now).

## Branch `oracle` (2026-09-12)

The backend was migrated from MongoDB to Oracle Database (FIAP) on the Challenge repo branch `oracle`,
deployed as a separate Render service. On this branch the app points to that backend:

- `API_BASE_URL` (defaultConfig + debug in `app/build.gradle`) = `https://challenge-totvs-backend-oracle.onrender.com/api/`.
- The `main` branch keeps `https://challenge-totvs-backend.onrender.com/api/` (MongoDB backend).
- REST contract is unchanged (same paths, DTOs, string IDs; IDs are now UUIDs instead of ObjectIds, which
  the app never validated), so no DTO or repository change was needed. Only comments/docs mentioning MongoDB
  were updated.
- Render free plan sleeps when idle: the first call after inactivity can take over a minute; the app's
  existing retry/error mapping covers it.

## Response cache (2026-09-12)

`network/ResponseCache` (process-scoped, in-memory, TTL per lookup) sits under `DataRepository`: dashboard,
transcription pages, insights (5 min), alerts and users are served from cache when younger than 60 s; every
read use-case has a `forceRefresh` overload that pull-to-refresh (and the dashboard retry button) uses to bypass it.
Writes invalidate what they affect (`criarTranscricao`/`importTotvsJson` -> dashboard, lists, insights, alerts;
`marcarAlertaVisto` -> alerts + dashboard; user changes -> users). `AuthRepository` clears the cache whenever the
session is cleared (logout or invalid refresh) because entries are per user. Nothing is persisted to disk.
The backend has its own Caffeine cache, so the two layers together keep Oracle traffic to one query burst per
window per user.

## Gravador de reunião (2026-09-15)
Recording, speaker-change detection and speech-to-text run **on the phone**; the backend is untouched
(the server `/api/transcricoes/audio` route exists but depends on Parakeet, which is off on the free
HF Space, so the phone sends the finished transcript through the normal `POST /api/transcricoes`).

```
com.tlean07.totvs.audio
├── dsp/            pure Java, unit-tested, no Android deps (shared with a JVM harness)
│   ├── Fft, FeatureExtractor      16 kHz PCM → 16 ms frames: energy, 13 MFCC (26 mel), pitch (autocorr), centroid
│   ├── VoiceActivityDetector      adaptive noise floor (+9 dB, min -52 dBFS), 3-frame onset, 300 ms hangover
│   ├── SpeakerProfile             mean MFCC c1..c12 (voiced frames) + mean log2 pitch; distanceTo() ≈ 1.0 = other person
│   ├── SpeakerClusterer           online centroids; new speaker if d > 1.05×inflation(short utterances); ≥40 voiced frames to found one
│   ├── SpeakerDiarizer            VAD segments + provisional label every 0.5 s + in-utterance split search (novelty z>1.35 or
│   │                              "tail closer to another known voice") + refine() second pass at the end (merge <0.95,
│   │                              re-attribute, blips follow neighbours, drop <1.5 s voices, renumber by first appearance)
│   ├── Resampler, WavIo           any rate/channels → 16 kHz mono; crash-safe WAV writer (+repair)
├── asr/            TranscriptionEngine, VoskEngine (queue + worker thread, word timestamps), TranscriptAssembler
│                   (words ↔ speaker segments by midpoint), TranscriptFormatter ([LOCUTOR N]: …), VoskModelManager
│                   (downloads vosk-model-small-pt-0.3, 32 MB, into filesDir/vosk once; offline afterwards)
├── AudioCapture (VOICE_RECOGNITION + NoiseSuppressor, 16 kHz with fallback rates), AudioPipeline (wav + diarizer + asr
│   + snapshots every 250 ms), RecorderSession (state machine, owned by MeetingRecorderService = foreground service
│   type microphone with pause/resume/stop notification actions; auto-pause on phone call, API 31+),
│   RecordingFinalizer (meta.json first, then WAV → M4A 48 kbps via MediaCodec), AudioFileDecoder + FileTranscriber
│   (import any file through the same pipeline), VocabularioTotvs (fixes "protege"→Protheus, "todos os fluidos"→TOTVS Fluig…)
└── store/ Recording (+unirFalantes, recalcularTemposDeFala) and RecordingStore (filesDir/gravacoes/<id>/meta.json + audio)
ui/gravador: GravadorActivity (clock, WaveformView coloured by speaker, chips, live transcript), RevisaoActivity (player
with SpeakerTimelineView, speaker rename/role/merge, turn edit, send text or audio), GravacoesActivity, ImportarAudioActivity.
```

Facts learned while tuning (TTS dialogues Antonio/Francisca/Duarte/Thalita via edge-tts, JVM harness in the session scratchpad):
- Same-speaker utterance distance 0.2–0.6 (long) but up to ~1.9 for windows under 0.5 s; different speakers 2.0–2.8.
  Everything short therefore gets an inflated threshold and can never found a speaker — a new voice is only created
  once it has talked ≥ ~0.65 s of voiced audio, and the refine() pass fixes what was mislabelled before that.
- Split search must normalise distance by the inflation curve, otherwise the shortest tail always "wins" and splits
  the same speaker. Accuracy on the synthetic dialogues: 2 speakers always found, 84–92 % of speech time correct,
  3-speaker dialogue 3 found after refine (4 live), two male voices 78 %.
- Vosk small pt: RTF ≈ 0.1 on a PC, fine on the phone with the queue; product names need the vocabulary fix.
- Emulator has no audio device: AudioRecord returns instantly, so the clock runs faster than wall time there
  (not on a real phone). `adb shell settings put global totvs_allow_capture 1` disables FLAG_SECURE in debug builds
  for screenshots; `app/src/debug/AndroidManifest.xml` exports Gravador/Gravacoes so `am start` works without login.
- The first manifest write silently failed once — always confirm the merged manifest has the service, or the
  foreground service crashes with ActivityNotFound/ServiceNotFound.

## Teclado + detalhe da análise (2026-09-15)
- `SafeAreaHelper.keyboardAwareSheet(dialog, content)` / `applyImeInset(view)`: the MaterialComponents
  BottomSheetDialog theme is NOT edge-to-edge, so its CoordinatorLayout consumes the insets and just gets
  shorter; the helper watches the sheet view's height, expands the sheet and scrolls the focused field above
  the keyboard by hand (requestRectangleOnScreen is useless there). Activities with inputs get
  `applyImeInset` on the scroll container. Used by the "Nova análise" sheet, the create-user sheet and Revisão.
- `TranscricaoResponse` mirrors the backend: `resumoExecutivo` lives inside `analise` (lists always showed
  "Análise em processamento…" before). `AnaliseDetalheActivity` (ui/analise) is the mobile version of the web
  `/transcricoes/[id]` page: header chips, resumo + persona/budget/confiança/sentimento tiles, próxima ação,
  "Além do óbvio" (insightsAdicionais), qualidade da conversa (indicadoresQualidade), métricas, produtos,
  oportunidades/riscos (consolidados com fallback nos brutos), alertas, trechos críticos, feedback para a
  LeanIA (POST/GET /transcricoes/{id}/feedback) e transcrição original; reprocessar quando status=ERRO.
- Two Claude sessions edited this repo at the same time on 2026-09-15 (one reset/recommitted the recorder);
  always `git status` + look for duplicated methods before building.

## Lote de melhorias (2026-09-15, tarde)
- `leania/` = cópia do `LeanIALocalAnalyzer` + DTOs Lombok do backend (compileOnly/annotationProcessor lombok
  1.18.48). `audio/LiveInsights` roda o analisador sobre os turnos a cada 8 s (thread própria) e uma vez no
  fim; resumo em `Recording.AnaliseLocal` (card "Sinais ao vivo" no gravador, "Prévia da LeanIA" na revisão,
  texto do "Compartilhar resumo").
- `VoicePrints` guarda a voz do usuário (SpeakerProfile.export) em prefs por userId; marcar-se como
  VENDEDOR na revisão ensina, `RecordingFinalizer` reconhece (distância < 0.9) e nomeia "Você".
- Marcadores: rótulo = palavras dos 6 s seguintes; long-press = digitar. `RecorderSettings` (bitrate,
  retenção, criptografia, biometria, consentimento). `RecordingCrypto` (EncryptedFile) cifra o M4A no fim;
  `RecordingStore.audioFile` decifra para cache/play e `releaseAudio` apaga.
- `TranscriptExporter` (TXT/PDF via PdfDocument + FileProvider `${applicationId}.files`).
- `network/PendingQueue` (fila offline em filesDir/fila, flush no NetworkCallback do TotvsApp, no onResume
  e no worker); `NetworkResult.networkError` marca IOException.
- `DataRepository.transcricoesTodas` monta a lista completa por páginas de 100 (o GET /transcricoes
  inteiro levava 47 s na Render); usada pela busca/filtros da aba Análises e pelo painel filtrado.
- `AlertNotes` (comentário "tratado" local), `security/AuditLog` (jsonl, card na aba Segurança),
  `widget/ResumoWidget` (prefs alimentadas pelo dashboard/worker), `work/AlertasWorker` (15 min, canal
  "alertas", só notifica ids não vistos; primeira execução só aprende).
- Ícone e splash voltaram ao logo TOTVS original a pedido do usuário (2026-09-15, noite). `splits.abi` gera APK por
  ABI + universal; o emulador usa o x86_64.
- Observado: com o backend lento, o token expirou no meio do carregamento paginado (401 "Nao autenticado");
  o loader tenta cada página 2x. O endpoint /transcricoes/cliente/{nome} com espaços precisa de
  URL-encode (Retrofit @Path faz).

## APK enxuto + bug da LeanIA local (2026-09-15, noite)
- R8 (`minifyEnabled` + `shrinkResources`) ligado também no debug; keep rules em `proguard-rules.pro` para
  DTOs Gson, Recording, leania, Vosk/JNA, Room/WorkManager (`WorkDatabase_Impl` sem keep crasha no
  startup). APK arm64 caiu de 31 MB para 16,9 MB.
- O packaging incremental do AGP deixa "buracos" no zip (APK de 32 MB com 17 MB de conteúdo). Para
  entregar, sempre `:app:clean :app:assembleDebug`.
- `Pattern.UNICODE_CHARACTER_CLASS` não existe no Android (IllegalArgumentException no <clinit> do
  `LeanIALocalAnalyzer`, derrubava o app ao iniciar a gravação). Trocado por `[\p{L}\p{Nd}_]+`; teste
  `LeanIALocalAnalyzerTest` cobre o initializer.

## Diarização offline nova + transcrever depois (2026-09-15, noite)
- Usuário relatou no S25 Ultra: "tudo cai como Pessoa 1" e "Enviar para análise" desabilitado. Causas:
  (1) o algoritmo ao vivo (limiar fixo de distância entre centróides) não separa vozes parecidas em
  sala com reverberação/celular na mesa; (2) o botão só habilita com texto, e a gravação não tinha
  texto porque o modelo Vosk nunca foi baixado no aparelho (não há erro, só fica sem ASR).
- `dsp/OfflineDiarizer` (+ `GaussianStats`, `FrameStore`): segunda passada no fim da gravação.
  CMVN sobre a gravação inteira; vetor = 12 MFCC + log-pitch + energia (c0); só frames vozeados;
  peças = falas ao vivo cortadas por teste de duas gaussianas; para K = 1, 2, 3… inicializa
  (dendrograma + sementes farthest-first), reatribui peças inteiras às vozes, e aceita K enquanto
  todo par de vozes passa o teste `ganho > P/2 + 2,6·n_eff` (n_eff = n1·n2/(n1+n2)); "resgate de
  órfãos" para quem só fala frases curtas; Viterbi por janelas de 0,5 s com custo de troca; vozes
  < 1,5 s absorvidas; `liveToFinal` para os nomes digitados ao vivo seguirem a pessoa certa.
- Corpus de teste: `scratchpad/tts/dialogo_*.wav` + versões `_fone`/`_sala` (degradar.py: reverb,
  falante longe −9/−12 dB, ruído rosa, AGC). Harness `scratchpad/harness/sweep.ps1` (-D props).
  Média 89,8 % de acerto 1:1 (antes: 55–80 % nos degradados; dois homens em sala 55 % → 99 %).
  Limite conhecido: duas vozes masculinas de mesmo pitch com uma delas longe ainda podem colar.
- Peças curtas (n_eff < 60) são indecidíveis (pares de vozes diferentes se sobrepõem aos pares da
  mesma voz); por isso as decisões só usam peças naturais ≥ 80 frames e agrupamentos inteiros —
  partições otimizadas por Viterbi inflam o ganho (≈3,6/n_eff) e não servem para decidir K.
- Revisão: "Transcrever agora" (baixa o modelo se preciso e roda `FileTranscriber.retranscribe`
  sobre o áudio guardado, mantendo nomes/papéis por índice); dica explica por que não há texto.
  Gravador: antes de gravar sem modelo, pergunta "Baixar e gravar / Gravar sem transcrição".
- R8 + Vosk/JNA confirmado funcionando no emulador via importação de WAV
  (`am start ImportarAudioActivity --es uri file:///data/data/<pkg>/files/x.wav`, exportada no
  manifest de debug; arquivo copiado com `run-as`).
- Prévia ao vivo: a cada ~12 s de áudio (e ao pausar) o `AudioPipeline` roda o `OfflineDiarizer`
  em segundo plano sobre uma cópia dos frames (`FrameStore.copy`, `SpeakerDiarizer.capturePreview`)
  e a tela do gravador adota os rótulos (mapa live→exibido; nomes digitados são remapeados por
  sobreposição via `Listener.onSpeakersRemapped`). No fim, `displayedToFinal` leva os nomes ao
  resultado final. A prévia para após 45 min de áudio; a passada final continua sempre.
- Gravações curtas (teste de 15 s): mínimos de voz/peça escalam com a fala total (15 %/8 %, piso
  40 frames) e os rótulos ao vivo entram como candidato de inicialização. Corpus curto
  (`dialogo_curto_*`): mulher+homem e troca rápida OK; dois homens de mesmo tom em 15 s ainda vira 1.
