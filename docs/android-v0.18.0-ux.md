# ES Medição 0.18.0 — nova seleção de sincronização

Implementação de 19/09/2026. VersionCode 36. Escopo: redesenhar a seleção de capturas, gerar APK, atualizar o emulador preservando dados e abrir a pasta do artefato.

## Interface implementada

- BottomSheetDialog Material com cantos arredondados, cabeçalho e rodapé fixos.
- RecyclerView com cards selecionáveis pela área inteira; borda, fundo e check distinguem a seleção sem depender somente da cor.
- Nome em destaque, data no formato brasileiro conforme o fuso do dispositivo e revisão abreviada. Nomes iguais não mesclam revisões.
- Chips de situação: no celular, pendente, recebido, falha, conflito e outra conta. Células vazias não são contadas como medidas.
- Busca por nome/revisão/data, filtros Todos/Pendentes/Falhas/Recebidos/Selecionados e ordenação por data ou nome.
- Seleção preservada ao buscar, filtrar e ordenar. Indicação quando há selecionados fora do filtro e ação Limpar seleção.
- Limite de 10 sem remover escolhas anteriores. Botão Continuar desabilitado com zero seleção e contador atualizado no próprio botão.
- Leitura dos arquivos fora da thread da interface. Estados de carregamento, falha, arquivo ilegível e lista vazia diferenciados.
- Identificação acessível do estado marcado; layout considera os espaços reservados pelo sistema e adapta o cabeçalho a telas mais baixas.

A nova tela usa tema próprio; o tema das demais telas do instrumento não foi substituído. A etapa de credenciais e o transporte continuam usando os fluxos existentes. Filtros e seleção não autenticam, não enfileiram e não enviam arquivos por conta própria.

## Validação executada

| Verificação | Resultado |
| --- | --- |
| Build Android e APK de testes | Aprovados |
| Testes unitários | 81 aprovados, zero falhas/pendentes |
| Testes da interface no emulador padrão | 44 verificações aprovadas |
| Mesma interface em largura de 360 dp e fonte a 130% | 44 verificações aprovadas |
| Regressão da sincronização no emulador | 54 verificações isoladas aprovadas |
| Lint Android | Zero erros, 109 avisos; nenhum apontamento nos três novos arquivos Java de produção |
| Preservação das capturas | 57 arquivos com SHA-256 idêntico antes/depois |

Os testes de interface exercitam seleção, limite, filtros, busca vazia, seleção fora do filtro, tamanho/posição do botão, revisão entregue e imutabilidade dos snapshots. Foram usados dados sintéticos, sem login, envio à nuvem ou comandos USB. As alterações temporárias de resolução e tamanho da fonte do emulador foram restauradas.

Também foram conferidas visualmente a tela real, a busca e a seleção. O método de entrada do AVD exibiu um painel flutuante; isso não foi tratado como comprovação de todos os teclados Android possíveis.

## Artefato

`artifacts/android-usb/ES-Medicao-v0.18.0.apk`

SHA-256: `0c9ae05c16b502ba8a79eccc4501a817802e8c8a9fefbe4d3e55212b3f82de7e`

APK de desenvolvimento, assinado de forma compatível com a instalação anterior do emulador. Instale como atualização, sem desinstalar o aplicativo. Não é uma publicação na Play Store nem foi criado certificado comercial nesta tarefa.

## Limites e rastreabilidade

Não foram modificados ThicknessSyncClient, ThicknessSyncQueue, ThicknessSyncRules, ThicknessSyncSnapshot, CaptureStore nem Dm5eProtocol; a comparação dos arquivos com a versão anterior confirmou isso. Não houve migrations, publicação do site ou envio de capturas reais nesta tarefa visual.

Backup do código, coletas, relatórios e imagens: `D:\@GitHubEnio\_deploy-backups\dm5ese-ux-20260919-161004`. Emulador: `DM5ESE_USB_API35`, serial `emulator-5580`.

Dependências de interface fixadas: Material Components 1.13.0 e RecyclerView 1.4.0. A consulta oficial confirmou a API BottomSheetDialog e a compatibilidade do RecyclerView com o compileSdk 35 já usado pelo projeto:
- https://developer.android.com/reference/com/google/android/material/bottomsheet/BottomSheetDialog
- https://developer.android.com/jetpack/androidx/releases/recyclerview
