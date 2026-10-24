# Smart Clean — Android nativo

Aplicativo de limpeza segura para Android, desenvolvido com Kotlin e Jetpack Compose.

## Mega Scan v0.2

- Painel real de espaço total, usado e disponível no armazenamento interno.
- Uma única autorização especial para **gerenciar todos os arquivos compartilhados** — sem escolher pastas manualmente.
- Varredura automática das unidades compartilhadas, incluindo Downloads, DCIM, Documents e Android/media (até 150.000 arquivos).
- Identificação de arquivos grandes (50 MiB ou mais), APKs e candidatos a temporários.
- Busca de duplicados por tamanho e SHA-256 (até 2 GiB por arquivo e 16 GiB de comparação por análise).
- Progresso da análise com contador de arquivos, tamanho e etapa atual; opção de interromper.
- Exibição da localização completa dos arquivos encontrados.
- Seleção individual e confirmação explícita para exclusão.
- Ação de marcar cópias extras preservando uma cópia de cada grupo, sem excluir automaticamente.
- Tudo local, sem conexão de rede, publicidade ou telemetria.

**Importante:** o acesso amplo ainda não inclui dados privados de outros apps, como `/Android/data` e `/Android/obb`. Não prometemos acelerar RAM, CPU ou limpar cache privado de outros aplicativos. O resultado pode ser parcial se pastas forem protegidas ou limites de processamento forem atingidos.

## Como abrir

1. Instale o Android Studio compatível com Android Gradle Plugin 9.4.0 e SDK 37.
2. Abra esta pasta como projeto Gradle.
3. Permita que o Android Studio sincronize as dependências. O projeto define Gradle 9.6.0 no `gradle/wrapper/gradle-wrapper.properties` (o *wrapper executável* não foi incluído).
4. Execute em aparelho ou emulador Android 11+ (API 30+).
5. Para gerar o APK de teste: **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.

O GitHub Actions compila o APK de teste automaticamente a cada push para `main`. Abra a aba **Actions**, selecione a execução **Build Android APK** e baixe o artefato **SmartClean-debug-APK** após o sucesso.

## Permissões e segurança

O manifesto declara `MANAGE_EXTERNAL_STORAGE`. A permissão **não é concedida automaticamente**: o usuário deve ativá-la explicitamente nas configurações do sistema. Ela é necessária para o Mega Scan que percorre arquivos compartilhados, sem a limitação do seletor de pastas. A exclusão não acontece durante o scan e exige marcação e confirmação de cada arquivo.

**Google Play:** essa permissão é restrita. Aplicativos cujo propósito central é gerenciamento/manutenção de arquivos podem solicitar avaliação, mas a Google Play exige declaração e aprovação, sem garantia de aceitação. Antes da publicação também são necessários política de privacidade, testes reais, assinatura de release e AAB.

## Estrutura

- `app/src/main/MainActivity.kt`: tela principal e fluxo de seleção/remoção.
- `app/src/main/MegaScanner.kt`: busca automática, análise e SHA-256.
- `app/src/main/AndroidManifest.xml`: permissão especial explícita.
- `app/build.gradle.kts`: dependências e opções Android.

O identificador do pacote ainda é provisório e pode ser alterado antes da publicação.
