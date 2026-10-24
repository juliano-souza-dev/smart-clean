# LimpaCel — Android nativo

Aplicativo de limpeza segura para Android, desenvolvido com Kotlin e Jetpack Compose.

## Primeira entrega

- Painel real de espaço total, usado e disponível no armazenamento interno.
- Escolha de uma pasta pelo seletor seguro do Android (Storage Access Framework).
- Varredura de arquivos da pasta e suas subpastas (até 10.000 arquivos).
- Identificação de arquivos grandes (100 MiB ou mais).
- Localização de duplicados por tamanho e hash SHA-256 de conteúdo (arquivos de até 512 MiB por item).
- Seleção individual e confirmação explícita para exclusão.
- Acesso às configurações de armazenamento nativas do Android.
- Tudo local, sem conexão de rede, publicidade ou telemetria.

**Importante:** a análise de arquivos restringe-se à pasta que o usuário autoriza; não representa uma varredura completa do celular. O Android não autoriza apps comuns a acessar indiscriminadamente os dados privados dos outros apps. Não há promessa de acelerar RAM, CPU ou limpar cache privado de outros apps.

## Como abrir

1. Instale o Android Studio compatível com Android Gradle Plugin 9.4.0 e SDK 37.
2. Abra esta pasta como projeto Gradle.
3. Permita que o Android Studio sincronize as dependências. O projeto define Gradle 9.6.0 no `gradle/wrapper/gradle-wrapper.properties` (o *wrapper executável* não foi incluído).
4. Execute em aparelho ou emulador Android 10+ (API 29+).
5. Para gerar o APK de teste: **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.

O APK ainda precisa ser compilado em uma instalação com Android SDK. Esta entrega contém o código-fonte, não um APK já compilado ou testado em dispositivo.

## Permissões e segurança

O manifesto não declara `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES` nem permissões amplas de mídia. O seletor do Android concede acesso somente à pasta escolhida. A exclusão não ocorre durante a análise e cada arquivo deve ser marcado pelo usuário.

Antes de publicar na Play Store serão necessários: nome e identidade visual definitivos, política de privacidade, testes em dispositivos reais, assinatura de release e revisão dos requisitos vigentes da loja.

## Estrutura

- `app/src/main/MainActivity.kt`: tela principal e fluxo de seleção/remoção.
- `app/src/main/StorageScanner.kt`: leitura segura de pastas, análise e SHA-256.
- `app/src/main/AndroidManifest.xml`: configuração sem permissões de alto risco.
- `app/build.gradle.kts`: dependências e opções Android.

Nome e identificador de pacote são provisórios e poderão ser alterados antes da publicação.

## Compilação automática no GitHub

O arquivo `.github/workflows/android-apk.yml` executa a compilação ao enviar alterações para `main` e também permite execução manual.

1. Abra a aba **Actions** do repositório.
2. Selecione **Build Android APK** e a execução desejada.
3. Quando a execução estiver verde, na seção **Artifacts** baixe `SmartClean-debug-APK`.
4. Extraia o ZIP do artefato para obter o arquivo `app-debug.apk` e instale-o no dispositivo de teste.

O APK debug é para testes. Para a publicação futura na Play Store será criado um pipeline de release com assinatura segura, sem armazenar chaves privadas no repositório.
