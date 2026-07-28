# Build do APK — GitHub Actions

O workflow `.github/workflows/android-build.yml` gera automaticamente:

- **APK Debug** — sempre, sem configuração. Instalável em qualquer aparelho.
- **APK Release assinado** — só quando os 4 secrets abaixo estiverem no repositório.

## Baixar o APK

1. Push no `main` (ou aba **Actions → Build Android APK → Run workflow**).
2. Aguarde ~3–5 min.
3. Abra a execução → seção **Artifacts** → baixe `ativador-tvdigital-debug` ou `ativador-tvdigital-release`.

---

## Gerar APK Release assinado (uma vez só)

### 1. Criar o keystore (na sua máquina local, com Java instalado)

```bash
keytool -genkey -v \
  -keystore tvdigital-release.keystore \
  -alias tvdigital \
  -keyalg RSA -keysize 2048 -validity 10000
```

Escolha e **guarde**:
- senha do keystore (`store password`)
- senha da chave (`key password`) — pode ser igual
- alias: `tvdigital`

⚠️ **Guarde o arquivo `.keystore` em local seguro.** Se perder, você nunca mais consegue publicar uma atualização assinada com a mesma identidade (usuários teriam que desinstalar/reinstalar o app).

### 2. Converter o keystore em base64

**Linux/Mac:**
```bash
base64 -w0 tvdigital-release.keystore > keystore.base64.txt
```

**Windows (PowerShell):**
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("tvdigital-release.keystore")) | Set-Content keystore.base64.txt
```

### 3. Adicionar os 4 secrets no GitHub

Vá em **Settings → Secrets and variables → Actions → New repository secret** e crie:

| Nome do secret              | Valor                                              |
| --------------------------- | -------------------------------------------------- |
| `ANDROID_KEYSTORE_BASE64`   | Conteúdo completo do arquivo `keystore.base64.txt` |
| `ANDROID_KEYSTORE_PASSWORD` | Senha do keystore                                  |
| `ANDROID_KEY_ALIAS`         | `tvdigital`                                        |
| `ANDROID_KEY_PASSWORD`      | Senha da chave                                     |

### 4. Rode o workflow novamente

Vai aparecer também o artifact `ativador-tvdigital-release` com o APK assinado, pronto para distribuir/publicar em loja.
