# Compilar APKs pelo GitHub Actions (automático)

Assim que voce fizer push desse projeto no seu repositorio GitHub,
o workflow em `.github/workflows/build-apk.yml` compila os dois APKs
sozinho, sem instalar nada na sua maquina.

## Passo a passo

1. Crie um repositorio no GitHub (pode ser privado).
2. Faca upload de TODA a pasta `android-app` para o repositorio
   (a pasta `.github` precisa ir junto, ela nao aparece no Finder
   do Mac por padrao — habilite arquivos ocultos ou use git).

   Pelo terminal:
   ```
   cd android-app
   git init
   git add .
   git commit -m "primeiro commit"
   git branch -M main
   git remote add origin https://github.com/SEU_USUARIO/SEU_REPO.git
   git push -u origin main
   ```

3. No GitHub, abra a aba **Actions**. O workflow "Build APKs"
   comeca a rodar automaticamente. Leva ~5 minutos na primeira vez.

4. Quando terminar (bolinha verde), clique no run mais recente,
   role ate o final da pagina e baixe o zip **apks** em "Artifacts".
   Dentro estarao:
   - `app-tvdigital-release-unsigned.apk`  (Ativador TV Digital)
   - `app-unitvfree-release-unsigned.apk`  (UniTV Free)

## APK "unsigned"

O workflow gera APKs sem assinatura de produção. Para instalar
no celular:

- Ative "Fontes desconhecidas" nas configuracoes do Android.
- Se o Android reclamar de assinatura, use a variante debug:
  troque no workflow `assembleTvdigitalRelease` por
  `assembleTvdigitalDebug` (e o mesmo para unitvfree). Debug ja vem
  assinado com a chave de desenvolvimento do Android e instala direto.

## Rodar manualmente

Sem push novo, va em **Actions → Build APKs → Run workflow** para
gerar novos APKs a qualquer momento.
