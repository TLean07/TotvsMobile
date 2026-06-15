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
  <img src="https://img.shields.io/badge/Dark%20Mode-DayNight-111B2E" alt="Dark Mode">
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

> Validado em aparelho real **Samsung Galaxy S25 Ultra (Android 16)**.

---

### Funcionalidades

- **Autenticação reaproveitada da web** — Login por e-mail/senha que reutiliza o JWT de acesso e o
  refresh token (cookie httpOnly) emitidos pelo mesmo backend
- **Desbloqueio biométrico** — Digital/rosto (e Samsung Pass via APIs Android) para restaurar a
  sessão local com segurança
- **Refresh automático de token** — Renovação transparente em 401, serializada e com logout seguro
- **Armazenamento seguro** — Tokens e identidade do dispositivo protegidos por
  `EncryptedSharedPreferences` + Android Keystore
- **CAS / vínculo de dispositivo** — Identificador estável por instalação, vinculado ao usuário e
  enviado para auditoria, com revogação local + servidor
- **Dashboard com gráficos** — Cards de métricas (transcrições, processadas, oportunidades, riscos,
  alertas, churn) + **gráfico donut** (sentimento) e **barras** (riscos/oportunidades por tipo)
- **Telas de Riscos e Oportunidades** — Listas consolidadas com filtros por tipo e severidade
- **Tela de Alertas** — Lista com filtros por status e prioridade, e ação "Marcar como visto"
- **Análises de IA** — Lista paginada; **nova análise** por texto **ou importação de JSON do TOTVS**
- **Administração de usuários** (admin) — Listar, criar (em bottom sheet), ativar/desativar
- **Modo escuro completo** — Tema claro/escuro que acompanha o sistema; fundos viram azul-escuro,
  textos, chips e gráficos se adaptam
- **Edge-to-edge** — Respeito a status bar, navigation/gesture bar e cutouts (inclusive S25 Ultra)
- **Resiliência a crash** — Registrador de stack trace em arquivo no dispositivo + guardas de ciclo de vida

---

### Arquitetura e Estrutura

```
app/src/main/java/com/tlean07/totvs/
|-- TotvsApp.java                      # Composition root / service locator (+ CrashReporter)
|-- network/
|   |-- ApiClient.java                 # OkHttp + Retrofit (cliente principal + refresh)
|   |-- ApiService.java                # Endpoints REST (os mesmos da web)
|   |-- AuthInterceptor.java           # Bearer + X-Request-ID + X-Device-Id (sem Origin)
|   |-- TokenAuthenticator.java        # 401 -> refresh unico -> retry / logout seguro
|   |-- PersistentCookieJar.java       # Refresh token (cookie httpOnly) cifrado
|   |-- DataRepository.java / Calls.java
|   +-- dto/                           # POJOs espelhando os DTOs do backend
|-- auth/
|   |-- AuthRepository.java
|   |-- biometric/BiometricHelper.java # Portado do LifeCode (Kotlin) -> Java, à prova de crash
|   +-- passkey/PasskeyManager.java    # Scaffold do Credential Manager
|-- cas/  DeviceManager.java, DeviceInfo.java   # CAS / device binding
|-- storage/  SecureStorage.java, SessionManager.java
|-- security/SecurityManager.java      # FLAG_SECURE, lock por inatividade
|-- util/  SafeAreaHelper, CrashReporter, AppExecutors, Callback, Logger
+-- ui/
    |-- SplashActivity, login/LoginActivity, MainActivity (BottomNavigationView)
    |-- dashboard/DashboardFragment.java
    |-- ai/AiFragment.java             # paginação + bottom sheet (texto / JSON TOTVS)
    |-- insights/                      # BaseInsightsActivity, RiscosActivity, OportunidadesActivity
    |-- alertas/AlertasActivity.java
    |-- profile/, security/, users/UsersActivity.java
    +-- components/                    # Adapters + DonutChartView / BarChartView
```

---

### Tecnologias Utilizadas

| Tecnologia | Uso |
| --- | --- |
| **Java 17** | Linguagem principal do app Android |
| **Android SDK 36** | compileSdk/targetSdk 36 (minSdk 29 / Android 10+) |
| **Material Components** | UI, tema claro/escuro (DayNight), bottom sheets, chips |
| **Retrofit + OkHttp** | Cliente HTTP, interceptors, authenticator de refresh, upload multipart |
| **Gson** | Serialização JSON |
| **AndroidX Security (Keystore)** | `EncryptedSharedPreferences` para tokens/segredos |
| **AndroidX Biometric** | `BiometricPrompt` / `BiometricManager` |
| **AndroidX Credentials** | Scaffold de passkey (Credential Manager / Samsung Pass) |
| **AndroidX Core SplashScreen** | Splash com a marca TOTVS |
| **Canvas customizado** | Gráficos donut e barras (sem biblioteca de terceiros) |
| **ViewBinding + SwipeRefreshLayout** | UI e pull-to-refresh |

---

### Telas

| Tela | Descrição |
| --- | --- |
| **Splash** | Restaura sessão silenciosamente / pede biometria |
| **Login** | Gradiente + card flutuante, campos Material com ícones, biometria |
| **Dashboard** | Cards de métricas + gráficos (donut/barras) + alertas recentes |
| **Riscos / Oportunidades** | Listas consolidadas com filtros por tipo/severidade |
| **Alertas** | Lista com filtros por status/prioridade + "Marcar como visto" |
| **Análises** | Lista paginada; nova análise por texto ou **importação de JSON do TOTVS** |
| **Usuários** (admin) | Listar/criar/ativar/desativar usuários |
| **Perfil / Segurança** | Dados, dispositivo (CAS), biometria, proteção de tela, logout |

---

### Como Executar

**Pré-requisitos:** Android Studio (Ladybug+), JDK 17+, backend do *FIAP Challenge* acessível.

1. Abra a pasta `Mobile` no Android Studio e confirme o `local.properties` (caminho do SDK).
2. URL do backend (por *build type* em `app/build.gradle`):
   - **debug** → `https://challenge-totvs-backend.onrender.com/api/` (Render, padrão — funciona direto)
   - troque para `http://10.0.2.2:8080/api/` se for usar um backend local no emulador
   - **release** → Render
3. Execute em um dispositivo/emulador com **Android 10+**.

**Gerar/instalar o APK pela linha de comando:**
```bash
./gradlew :app:assembleDebug                 # gera o APK de debug
./gradlew :app:testDebugUnitTest             # testes unitários
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **Tirar prints**: as telas autenticadas usam `FLAG_SECURE`. Para liberar capturas, em
> **Segurança** desative "Proteger telas sensíveis"; reative depois para rebloquear.

---

### Fluxo de Autenticação e Tokens

```
1. Login (e-mail/senha) -> POST /api/auth/login
2. Backend retorna accessToken (JWT HS256, 15 min) + cookie httpOnly refresh_token
3. Access token fica SO em memoria; refresh fica no cookie cifrado (Keystore)
4. Toda request leva Authorization: Bearer <accessToken> + X-Device-Id
5. Em 401, o TokenAuthenticator chama POST /api/auth/refresh (1x, serializado, ignora /auth/*)
6. Refresh OK -> novo access token + cookie rotacionado -> request re-tentada
7. Refresh falhou (expirado/revogado) -> sessao limpa -> volta para o Login
8. Biometria desbloqueia o restore local; o backend valida tudo no servidor
```

---

### Backend Compartilhado (reaproveitado)

```
Autenticacao
  POST /api/auth/login | refresh | logout

Dados
  GET   /api/dashboard
  GET   /api/transcricoes  |  /paged?page&size  |  /{id}  |  /insights
  POST  /api/transcricoes                      -> cria + analisa (backend -> IA Hugging Face)
  POST  /api/transcricoes/import/totvs         -> importa JSON do TOTVS (multipart)
  GET   /api/alertas | /alertas/novos          PATCH /api/alertas/{id}/visto
  GET   /api/users   POST /api/users           PATCH /api/users/{id}/activate|deactivate  (admin)

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
