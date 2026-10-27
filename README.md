# Smart Clean — Android nativo

Aplicativo Kotlin/Jetpack Compose, compatível com Android 11 ou superior (`minSdk 30`, `targetSdk 36`). **100% local**, sem conta, anúncios, telemetria ou envio dos nomes/conteúdos dos arquivos a servidores.

## Recursos implementados

- **Início:** espaço real em uso e disponível, Mega Scan sob demanda, resultados e atalhos.
- **Arquivos:** categorias de grandes (limite ajustável, padrão 100 MB), duplicados SHA-256, temporários, ocultos, miniaturas, mídias de apps, mídias possivelmente fora do índice do Android, fotos, vídeos, capturas, músicas, documentos, arquivos compactados e APKs baixados.
- **Mega Scan inteligente:** varredura de armazenamento compartilhado permitido pelo Android, hash somente após agrupamento por tamanho, orçamento padrão de leitura de 16 GiB para comparações e opção de aprofundar; comparação interrompida se o estado térmico ficar elevado. Limite de 150 mil arquivos/30 mil pastas por varredura, com indicação de resultado parcial. Progresso, notificação persistente e cancelamento. A execução em segundo plano está sujeita às regras e aos limites do Android.
- **Exclusão segura:** nenhum arquivo vem pré-marcado. Seleção individual, em lote ou só de cópias excedentes de grupos duplicados. Confirmação obrigatória; exclusão definitiva exige segunda confirmação. Pastas privadas do Android, lixeira e pastas indicadas como **Não tocar** são protegidas.
- **Lixeira recuperável:** movimenta arquivos para área oculta no mesmo volume, sem sobrescrever o nome original na restauração; arquivos na lixeira **continuam ocupando espaço** até serem excluídos de vez. O usuário escolhe entre restaurar e excluir permanentemente.
- **Saúde:** temperatura real da bateria, status térmico do Android, RAM total/disponível, tempo em primeiro plano dos aplicativos quando autorizado, ações assistidas nas Configurações e medições antes/depois. Não há botão falso de resfriar, liberar RAM nem comando oculto de encerrar outros aplicativos.
- **Histórico local:** resultados por arquivo, inclusive falhas, tamanho, espaço livre, temperatura da bateria e RAM antes/depois da operação. Pode ser apagado pelo usuário.

## Limitações importantes

- `MANAGE_EXTERNAL_STORAGE` não dá acesso a dados privados de outros apps em `Android/data`, `Android/obb` nem a cache privado. O Android impede que um app comum force o encerramento de outros apps. A lista de aplicativos mostra **uso recente em primeiro plano**, não consumo de CPU em tempo real.
- A lista de APKs refere-se aos **arquivos instaladores `.apk` encontrados**; não é possível concluir com segurança apenas por seu nome que algum instalador está sem uso.
- Mídias fora do índice do Android são **candidatas à revisão**, não prova de lixo. Identificação pode ser parcial em versões recentes do Android.
- Acesso especial a arquivos precisa ser habilitado pelo usuário nas configurações. A Google Play exige **avaliação e aprovação específicas** para aplicativos que solicitam acesso amplo; instalar um APK por fora não concede a permissão automaticamente.
- As medições antes/depois não estabelecem causalidade. Temperatura lida é **da bateria**, não da CPU, e o Android gerencia a RAM sozinho.

## Compilação e testes

1. Abra o repositório em Android Studio com SDK 37, Java 17 e suporte ao Android Gradle Plugin 9.4.0/Gradle 9.6.0.
2. Rode `gradle :app:testDebugUnitTest :app:assembleDebug` ou use a workflow **Build Android APK** em GitHub Actions.
3. O APK de depuração tem sufixo de pacote `.beta`, podendo coexistir com uma futura versão assinada da Play Store. Uma build *debug* não deve ser distribuída como produção.
4. O artefato padrão de Actions é baixado em ZIP pela própria interface do GitHub. A workflow manual **Publicar APK aprovado** publica o `.apk` **direto** na página de Releases, após aprovação de testes e versão. Ela não roda a cada push.

Repositório: `juliano-souza-dev/smart-clean`. Nenhum APK deve ser publicado antes da validação do conjunto completo pelo responsável.

## Organização

| Arquivo | Responsabilidade |
| --- | --- |
| `SmartCleanActivity.kt` | Navegação inferior, revisões e ações explícitas |
| `MegaScanner.kt`, `ScanPolicy.kt`, `FileDigests.kt` | Busca, limites, categorias, proteção e duplicatas |
| `ScanService.kt` | Execução em foreground com notificação/cancelamento |
| `TrashManager.kt` | Movimentação, restauração e exclusão da lixeira |
| `DeviceDiagnostics.kt` | Leituras reais de saúde, bateria, RAM e uso |
| `HistoryRepository.kt` | Histórico local e detalhado |
| `app/src/test` | Testes de classificação, políticas, SHA-256 e snapshot |
