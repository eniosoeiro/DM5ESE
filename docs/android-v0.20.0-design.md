# ES Medição 0.20.0 — identidade visual aplicada ao aplicativo

VersionCode 38. Implementação Android nativa no módulo `android-usb-probe`. As imagens aprovadas orientaram a identidade; não foram usadas como telas estáticas nem como substitutas dos controles reais.

## Interface

Nova tela Início com marca ES vetorial, apresentação em azul profundo/verde, estado real da conexão USB, quatro atalhos, resumo dos recibos locais e capturas recentes. Atalhos: Nova matriz, Arquivos salvos, Sincronizar e Conexão USB. Nomes, datas e quantidades vêm dos arquivos disponíveis; não são dados de demonstração.

Navegação fixa: Início, Medições, Visor, Nuvem e Ajustes. A última matriz/página e a aba selecionada são conservadas na recriação da Activity. Abrir uma captura ou criar uma matriz leva à página Medições.

A Nuvem tem espaço próprio para a conta salva, seleção explícita de arquivos, explicação do hash e acesso ao Med.Online. A informação de conta salva não é apresentada como prova de sessão online válida: a autenticação continua sendo conferida no envio.

Ajustes agrupa conta, conexão USB, dados do arquivo, exportações CSV/JSON, ajuda e log de suporte. Operações de gravação/criação/exclusão no instrumento ficam separadas e recolhidas por padrão; seus controles e confirmações existentes foram preservados. O atalho Conexão USB abre a seção correspondente.

Botões, campos, biblioteca, seleção de sincronização e temas compartilham cores, ícones e espaçamentos. A velocidade no cabeçalho da matriz foi compactada e continua identificada como parâmetro do arquivo, não como consulta atual ao instrumento. O visor conserva distinção entre leitura atual, ausência e valor retido.

## Marca e recursos

Logo ES com marcações de medição, versões claras/escuras e ícone adaptativo Android com variante monocromática. Família de 17 ícones vetoriais. Recursos nativos estão em `app/src/main/res`; SVGs editáveis estão em `android-usb-probe/branding`.

A interface não simula bateria do instrumento, clientes, identidade conectada, notificações ou sincronização automática. Coleta offline, envio explícito, conta lembrada e confirmação por hash continuam com o comportamento existente.

## Validações executadas

| Verificação | Resultado |
| --- | --- |
| Build do aplicativo e APK de testes | Aprovado |
| Testes unitários | 81 aprovados |
| Navegação, estados e ações do novo layout | 92 verificações no emulador |
| Mesmo layout em 360 dp, fonte a 130% | 92 verificações aprovadas |
| Mesmo layout em orientação horizontal | 92 verificações aprovadas |
| Seleção de sincronização | 44 verificações, também repetidas em 360 dp |
| Sessão e hash | 34 verificações isoladas aprovadas |
| Regressão de sincronização | 54 verificações isoladas aprovadas |
| Fluxo de células | Prévia, vazios, acoplamento, expiração, persistência e cancelamento aprovados com arquivos sintéticos isolados |
| Lint | Zero erros; 118 avisos registrados, sem desligar regras |

O teste de células tinha uma expectativa anterior à entrada manual já existente: esperava o botão desativado depois de parar o USB. O código de produção anterior e o atual oferecem explicitamente Informar valor no celular nessa condição. O teste foi atualizado para verificar que o valor USB antigo não pode ser salvo, que a entrada manual requer seu próprio formulário e que abrir/cancelar esse formulário não grava dados. Nenhuma regra de gravação foi alterada para satisfazer o teste.

As alterações temporárias de tamanho/fonte do AVD foram restauradas. As 57 capturas existentes foram comparadas por SHA-256 antes/depois; permanecem idênticas. O arquivo criptografado da conexão salva também manteve o mesmo hash. Senha e tokens não foram exibidos nem enviados pelos testes.

Comparação de fonte confirmou intactos: CaptureStore, Dm5eProtocol, Dm5eUsb, ThicknessSyncClient, ThicknessSyncQueue, ThicknessSyncRules, ThicknessSyncSnapshot, ThicknessSessionStore e ThicknessSyncDialog. Não houve migração de banco, publicação do site, envio de capturas pessoais nem comando para instrumento físico nesta tarefa.

## Entrega e limites

APK: `artifacts/android-usb/ES-Medicao-v0.20.0.apk`, 14.945.146 bytes.

SHA-256: `06fbf17ff7ec922f582f693ddd6b5d9bf4c28c06052ebf0069e930daef09a6ea`.

Instalação por atualização no AVD `DM5ESE_USB_API35`, serial `emulator-5580`, sem desinstalar ou apagar dados. O aplicativo continua configurado para o backend de produção, mas o APK tem assinatura de desenvolvimento compatível com as versões anteriores. Não é publicação na Play Store e não atualiza automaticamente os aparelhos físicos.

As validações de rede/autenticação usam transporte e dados sintéticos. Não equivalem a uma nova homologação física do USB ou a um novo envio autenticado real. Os limites anteriores de sincronização e MFA permanecem.

Backup de código, capturas, relatórios e imagens reais do emulador: `D:\@GitHubEnio\_deploy-backups\dm5ese-design-20260919-182657`. Logs Gradle: `%TEMP%\dm5ese-v020-*.log`.
