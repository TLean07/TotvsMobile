<h1 align="center">TOTVS Mobile</h1>
<p align="center">Aplicativo Android nativo (Java) de inteligência conversacional — segundo cliente do mesmo backend, banco e IA da versão web</p>

<p align="center">
  <img src="app/src/main/res/drawable/totvs_logo.png" width="280" alt="TOTVS Logo" />
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white" alt="Java 17">
  <img src="https://img.shields.io/badge/Android-10%2B%20(API%2029)-3DDC84?logo=android&logoColor=white" alt="Android 10+">
  <img src="https://img.shields.io/badge/Retrofit%20%7C%20OkHttp-48B983?logo=square&logoColor=white" alt="Retrofit/OkHttp">
  <img src="https://img.shields.io/badge/Keystore%20%7C%20Biometric-1A365D?logo=android&logoColor=white" alt="Keystore/Biometric">
  <img src="https://img.shields.io/badge/Material%20Components-1A365D?logo=materialdesign&logoColor=white" alt="Material">
  <img src="https://img.shields.io/badge/FIAP-Challenge-E4002B" alt="FIAP">
</p>

---

### Sobre o Projeto

O **TOTVS Mobile** é um aplicativo Android nativo desenvolvido em **Java** que funciona como uma
**segunda forma de acesso** ao sistema de inteligência conversacional do *FIAP Challenge*. A
versão web (Next.js) e a versão mobile são dois clientes diferentes consumindo **o mesmo backend
Java (Spring Boot, hospedado no Render)**, **o mesmo banco MongoDB** e **o mesmo serviço de IA
(Hugging Face)** — tudo através do backend.

Nada é duplicado: não há backend separado, banco separado nem autenticação paralela. O app reaproveita
**os mesmos usuários, tokens, permissões e dados** da versão web, adicionando uma camada de segurança
mobile com **biometria**, **armazenamento via Android Keystore** e **vínculo de dispositivo (CAS)**.

---

### Funcionalidades

- **Autenticação reaproveitada da web** — Login por e-mail/senha que reutiliza o JWT de acesso e o
  refresh token (cookie httpOnly) emitidos pelo mesmo backend
- **Desbloqueio biométrico** — Digital/rosto (e Samsung Pass via APIs Android) para restaurar a
  sessão local com segurança
- **Refresh automático de token** — Renovação transparente em 401, serializada e com logout seguro
  em caso de falha
- **Armazenamento seguro** — Tokens e identidade do dispositivo protegidos por
  `EncryptedSharedPreferences` + Android Keystore
- **CAS / vínculo de dispositivo** — Cada instalação recebe um identificador estável vinculado ao
  usuário, enviado para auditoria no backend, com revogação local + servidor
- **Dashboard** — Métricas de transcrições, oportunidades, riscos e alertas com pull-to-refresh
- **Análises de IA** — Lista de transcrições analisadas e envio de nova transcrição para análise
- **Perfil e Segurança** — Dados do usuário, dispositivo vinculado, toggles de biometria e proteção
  de tela, logout e revogação
- **Edge-to-edge** — Respeito total a status bar, navigation/gesture bar e cutouts (inclusive
  Samsung Galaxy S25 Ultra)

---

### Arquitetura e Estrutura

```
app/src/main/java/com/tlean07/totvs/
|-- TotvsApp.java                      # Composition root / service locator
|-- network/
|   |-- ApiClient.java                 # Stack OkHttp + Retrofit (cliente principal + refresh)
|   |-- ApiService.java                # Endpoints REST (os mesmos da web)
|   |-- AuthInterceptor.java           # Bearer + X-Request-ID + X-Device-Id (sem Origin)
|   |-- TokenAuthenticator.java        # 401 -> refresh unico -> retry / logout seguro
|   |-- PersistentCookieJar.java       # Refresh token (cookie httpOnly) cifrado
|   |-- DataRepository.java / Calls.java
|   |-- NetworkResult / ErrorMapper / AuthEvents
|   +-- dto/                           # POJOs espelhando os DTOs do backend
|-- auth/
|   |-- AuthRepository.java            # login / silentRefresh / logout
|   |-- biometric/BiometricHelper.java # Portado do LifeCode (Kotlin) para Java
|   +-- passkey/PasskeyManager.java    # Scaffold do Credential Manager (passkey)
|-- cas/
|   |-- DeviceManager.java             # Identidade do dispositivo + vinculo ao usuario
|   +-- DeviceInfo.java                # Espelha a colecao mobile_devices planejada
|-- storage/
|   |-- SecureStorage.java             # EncryptedSharedPreferences (Android Keystore)
|   +-- SessionManager.java            # Access token em memoria + flags de sessao
|-- security/SecurityManager.java      # FLAG_SECURE, lock por inatividade, debug detection
|-- util/
|   |-- SafeAreaHelper.java            # WindowInsets / edge-to-edge (S25 Ultra, cutouts)
|   +-- AppExecutors / Callback / Logger (sanitizado)
+-- ui/
    |-- SplashActivity.java
    |-- login/LoginActivity.java
    |-- MainActivity.java              # BottomNavigationView
    |-- dashboard/DashboardFragment.java
    |-- ai/AiFragment.java
    |-- profile/ProfileFragment.java
    |-- security/SecurityFragment.java
    +-- components/                    # StatAdapter, AlertaAdapter, TranscricaoAdapter
```

---

### Tecnologias Utilizadas

| Tecnologia | Uso |
| --- | --- |
| **Java 17** | Linguagem principal do app Android |
| **Android SDK 36** | compileSdk/targetSdk 36 (minSdk 29 / Android 10+) |
| **Material Components** | Sistema de design e componentes UI (tema claro/escuro) |
| **Retrofit + OkHttp** | Cliente HTTP, interceptors, authenticator de refresh |
| **Gson** | Serialização JSON |
| **AndroidX Security (Keystore)** | `EncryptedSharedPreferences` para tokens/segredos |
| **AndroidX Biometric** | `BiometricPrompt` / `BiometricManager` |
| **AndroidX Credentials** | Scaffold de passkey (Credential Manager / Samsung Pass) |
| **AndroidX Core SplashScreen** | Splash com a marca TOTVS |
| **ViewBinding + SwipeRefreshLayout** | UI e pull-to-refresh |

---

### Como Executar

**Pré-requisitos:**
- Android Studio (Ladybug ou superior)
- JDK 17+
- Backend do *FIAP Challenge* em execução (local ou no Render)

**Passos:**

1. Clone o repositório e abra a pasta `Mobile` no Android Studio.

2. Confirme o `local.properties` apontando para o seu Android SDK.

3. Configure a URL do backend (já definida por *build type* em `app/build.gradle`):
   - **debug** → `http://10.0.2.2:8080/api/` (backend local via loopback do emulador)
   - **release** → `https://challenge-totvs-backend.onrender.com/api/`

4. Execute em um dispositivo/emulador com **Android 10+**.

**Gerar o APK pela linha de comando:**
```bash
./gradlew :app:assembleDebug      # APK de debug
./gradlew :app:testDebugUnitTest  # Testes unitarios
```
O APK fica em `app/build/outputs/apk/debug/app-debug.apk`.

---

### Fluxo de Autenticação e Tokens

```
1. Login (e-mail/senha) -> POST /api/auth/login
2. Backend retorna accessToken (JWT HS256, 15 min) + cookie httpOnly refresh_token
3. Access token fica SO em memoria; refresh fica no cookie cifrado (Keystore)
4. Toda request leva Authorization: Bearer <accessToken> + X-Device-Id
5. Em 401, o TokenAuthenticator chama POST /api/auth/refresh (1x, serializado)
6. Refresh OK -> novo access token + cookie rotacionado -> request re-tentada
7. Refresh falhou (expirado/revogado) -> sessao limpa -> volta para o Login
8. Biometria desbloqueia o restore local; o backend valida tudo no servidor
```

---

### Backend Compartilhado (reaproveitado)

```
Autenticacao
  POST /api/auth/login     -> { accessToken, expiresInSeconds, user{ id, email, name, role } } + Set-Cookie refresh_token
  POST /api/auth/refresh   -> novo accessToken + cookie rotacionado
  POST /api/auth/logout    -> revoga a sessao de refresh (MongoDB)

Dados
  GET   /api/dashboard            -> metricas + alertas recentes + ultimas transcricoes
  GET   /api/transcricoes         -> lista de analises
  GET   /api/transcricoes/{id}    -> detalhe da analise
  POST  /api/transcricoes         -> cria + analisa (backend -> IA Hugging Face)
  GET   /api/alertas              -> alertas
  PATCH /api/alertas/{id}/visto   -> marca como visto

Seguranca
  - Access token: JWT HS256 (claims sub/email/name/role/iat/exp)
  - Refresh token: opaco, guardado como hash SHA-256 na colecao refresh_tokens
  - Roles: ADMIN, USER (validacao server-side; o app nunca decide permissao final)
```

> Endpoints mobile/passkey dedicados (`/api/mobile/devices/*`, `/api/mobile/auth/passkey/*`) estão
> projetados de forma aditiva/retrocompatível como próximo passo — sem quebrar a versão web.

---

### Autor

<table>
  <tr>
    <td align="center">
      <a href="https://github.com/TLean07">
        <img src="https://github.com/TLean07.png" width="100px;" alt="TLean07"/>
        <br />
        <sub><b>TLean07<b></sub>
      </a>
      <br />
      <sub>RM 561344</sub>
    </td>
  </tr>
</table>
