# ES Medição 0.17.0 — sincronização com IntegraNR

Entrega de 19/09/2026. Android Java em `android-usb-probe`, pacote `com.dm5ese.usbprobe`, versionCode 35.

## Uso

No Android: salve a captura, toque **Sincronizar**, selecione de 1 a 10 revisões, continue e entre com sua conta IntegraNR. Confirme o vínculo à conta/empresa e toque **Vincular e enviar**. A senha não é salva. Coleta e matrizes continuam disponíveis offline, sem login obrigatório.

Na web: `/calibracao/med-online?tipo=espessuras`, aba **Medição de espessuras**. Use a mesma conta do envio. A lista atualiza a cada 15 segundos enquanto a aba está aberta, ou pelo botão Atualizar espessuras. Confira a matriz e o destino antes de incorporar pontos à inspeção.

## Implementação

Configuração pública do mesmo Supabase do site; schema nr13 explícito no REST. Build e cliente recusam configuração administrativa. Sessão curta exclusiva de sincronização: não ocupa nem substitui a lease de aquisição do ES Calibra. Logout limita-se à autenticação deste envio.

Fila privada AtomicFile grava snapshot e vínculo usuário/empresa antes da rede. Revisão imutável, hash, tentativas, intervalo e recibo são persistidos separadamente da captura técnica. A seleção retoma pendências e respeita backoff; após cinco falhas exige repetição explícita. Interrupção na última tentativa também permite recuperação explícita.

O recibo só é confirmado após conferir no servidor revisão, linhagem e hashes cliente/canônico. Timeout após gravação pode ser repetido pela mesma identidade sem duplicar capturas. Decimal em texto, vazios, metadados e evidência são preservados. Não preenche aprovação, coordenadas, espessura mínima ou valores inventados. A linhagem do rascunho é mantida na confirmação do instrumento.

Não foram adicionados comandos USB nem alterado o protocolo do instrumento. Capturas antigas não são apagadas ou enviadas automaticamente.

## Validações executadas

- Android: 69 testes unitários, sem falhas; build do APK e APK de testes aprovados.
- Android lint: zero erros, 104 avisos não suprimidos.
- Emulador DM5ESE_USB_API35, porta 5580: 54 verificações isoladas de fila, recibos, conta/empresa, precisão, interrupção, HTTP e linhagem. Transporte simulado, sem USB e sem escrita em produção.
- Instalação por atualização: as 57 capturas preexistentes foram comparadas por SHA-256 antes/depois, sem mudança. Emulador aberto na seleção, sem arquivos pré-selecionados.
- Web: 950 testes em 94 arquivos; TypeScript, build e lint dos arquivos alterados aprovados.
- Supabase de produção: 24 verificações SQL aprovadas em transação revertida. Contrato existente reutilizado; nenhuma migration nova necessária nesta entrega.

## Artefato e limites

APK: `artifacts/android-usb/ES-Medicao-v0.17.0.apk`. Assinado com a chave de desenvolvimento compatível com a instalação do emulador. Aponta para produção, mas não é publicação na Play Store nem release com certificado comercial. Instale como atualização; não desinstale para preservar coletas.

O teste real de ponta a ponta depende do login e seleção pelo operador. Não foi declarado sucesso autenticado a partir dos mocks, nem foram enviadas coletas do usuário automaticamente. MFA não é suportado nesta versão. A lista Android apresenta as 100 revisões mais recentes, em lotes de até 10. Snapshots acima de 3 MiB são recusados sem truncar evidências. Sincronização somente em primeiro plano, pausada ao sair; não há serviço autônomo em segundo plano.

Backup de código e das 57 capturas, logs e evidências: `D:\@GitHubEnio\_deploy-backups\dm5ese-cloud-20260919-152725`. A listagem no servidor pertence à conta coletora, protegida por RLS. Incorporação à inspeção é confirmação separada e não sobrescreve medições existentes.

## Compilação

JDK 17 e Android SDK 35. O build lê somente as duas variáveis públicas da pasta irmã `gestaonr13-app/.env.local`, ou recebe ES_SYNC_URL e ES_SYNC_PUBLISHABLE_KEY por ambiente/propriedades Gradle. Nunca forneça chave administrativa.

```powershell
cd android-usb-probe
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
```

O módulo Android ainda não estava versionado. Esta entrega preserva o código existente e o registra com as alterações de sincronização. Outros arquivos locais não relacionados ficam preservados fora deste commit.
