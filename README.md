# Ativador TV Digital — APK

Projeto Android nativo (Kotlin) do ativador. Consome o endpoint
`POST https://tvdigital.lovable.app/api/public/activate` do painel,
baixa o arquivo `.config` retornado e grava em
`/storage/emulated/0/Android/.config` no aparelho.

## Como compilar

1. Instale **Android Studio Hedgehog+** (JDK 17 embutido).
2. Abra a pasta `android-app/` como projeto (File → Open).
3. Aguarde o Gradle sincronizar (baixa dependências).
4. `Build → Generate Signed Bundle / APK → APK`.
5. Crie/selecione seu **keystore** (`.jks`), preencha senha e alias.
6. Escolha **release**, marque V1 e V2 signature, finalize.
7. O APK assinado sai em `android-app/app/release/app-release.apk`.
8. Faça upload desse APK no painel admin (`/admin`).

## Configuração

- Endpoint padrão: `https://tvdigital.lovable.app/api/public/activate`
  (definido em `app/src/main/java/app/tvdigital/ativador/Api.kt`).
- Para trocar o `applicationId` do app, edite `app/build.gradle.kts`
  (`namespace` e `applicationId`).
- Ícone: substitua os PNGs em `app/src/main/res/mipmap-*`.

## Permissões usadas

- `INTERNET` — chamar o endpoint e baixar arquivos.
- `MANAGE_EXTERNAL_STORAGE` (Android 11+) / `WRITE_EXTERNAL_STORAGE` (≤ 10) —
  gravar `Android/.config` na raiz do armazenamento.
- `REQUEST_INSTALL_PACKAGES` — instalar o APK do pack baixado.
- `READ_PHONE_STATE` — obter identificador do aparelho.

## Fluxo

1. Usuário digita o código de ativação.
2. App faz `POST` com `action=activate` + `device_id` (ANDROID_ID).
3. Backend responde com `config_file_url`, `config_file_name`,
   `pack_zip_url`, `expires_at`.
4. App baixa o `.config` e salva em `/storage/emulated/0/Android/<nome>`.
5. App baixa o APK do `pack_zip_url` e abre o instalador do Android.
6. Após instalar, app chama `action=confirm_pack_download`.
