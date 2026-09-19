> Atualização 0.19.0: conexão lembrada com proteção do Android, hash por arquivo e confirmação antes de sobrescrever. Veja `../docs/android-v0.19.0-conta-hash.md`.

# ES Medição — aplicativo Android

Versão atual: **0.19.0**, com envio de capturas para IntegraNR / Med.Online / Medição de espessuras.
APK: `../artifacts/android-usb/ES-Medicao-v0.19.0.apk`. Instale como atualização para preservar as capturas.
Nova interface de seleção: `../docs/android-v0.18.0-ux.md`.
Sincronização, configuração pública e limites: `../docs/android-v0.17.0-sincronizacao.md`.
As seções seguintes também contêm registros históricos das versões anteriores.

Aplicativo Java nativo, independente do programa Windows. Android 13 ou superior.
Lê arquivos do instrumento por USB CDC, mostra posição/espessura, salva capturas
localmente e exporta CSV ou JSON. Versão experimental 0.3.0-reader.

A aba **Medições** mostra a velocidade em destaque e a matriz com números nas
linhas e letras nas colunas. **Importar via USB** abre os controles de conexão.
A aba **Configurações** apresenta os metadados do arquivo (série, firmware,
velocidade, unidade e estrutura), sem tratá-los como consulta ao estado atual
do instrumento. O modelo e as especificações do cabeçote ainda não foram
identificados; a ficha sinaliza essa ausência explicitamente.

## Usar

1. Conecte o DM5E. No celular, é necessário suporte USB Host e adaptador OTG.
2. Abra **DM5ESE · Medições**, toque **Conectar e listar arquivos** e autorize o USB.
3. Selecione um arquivo e toque **Baixar arquivo selecionado**.
4. Role a tela para ver os pontos. A captura completa é salva automaticamente.
5. **Salvos no celular** abre capturas anteriores sem consultar o instrumento.
6. **Exportar CSV/JSON** permite escolher o destino pelo seletor do Android.

A sincronização com IntegraNR é explícita: toque Sincronizar, selecione revisões e entre com sua conta.
A coleta continua offline; não há envio automático de todo o histórico. Consulte as notas 0.17.0 acima.
Desinstalar o app apaga seu armazenamento privado; preserve as capturas por atualização e exportação.

## Validação com equipamento físico em 15/09/2026

- Emulador 37.1.11, AVD `DM5ESE_USB_API35`, Android 15/API 35, x86_64.
- DM5E físico encaminhado do Windows por USB: `C251:1705`, CDC ACM, 115200 8N1.
- Lista real de 17 arquivos recebida; arquivos **01** e **02** baixados, 16 pontos cada.
- Todos os valores e posições dos arquivos 01 e 02 conferidos contra a DLL Windows.
  Comparação exata após conversão para float32, representação usada pela DLL;
  o Android conserva os decimais transmitidos pelo instrumento.
- Falhas reais de transmissão também foram detectadas: diretório incompleto e
  checksum incorreto. A captura anterior foi preservada, sem salvar parciais.
- Arquivo 02 baixado novamente após ajuste de intervalo entre confirmações.
  Arquivo 03 falhou por checksum no bloco 36: a estabilidade não está comprovada.
- Captura reaberta após encerrar o processo, sem nova leitura USB. CSV exportado
  pelo seletor Android e conferido com 16 registros. JSON exportado e conferido
  pelo identificador da captura validada.
- Testes unitários do protocolo e `assembleDebug testDebugUnitTest lintDebug`
  passaram. Lint mantém avisos de localização, ícone, backup e target SDK.

**Limites:** o encaminhamento USB do emulador apresentou perda intermitente de
bytes. A listagem repete até três vezes; se houver erro, reconecte o equipamento
e liste novamente. A transferência ainda não está validada para uso contínuo,
nem em celular físico com OTG. Foi validado o formato Grid LR, versão 2.1, em mm;
outros formatos, estados de leitura e unidades são rejeitados explicitamente.
Não considerar os demais arquivos validados apenas porque aparecem na lista.

Capturas e logs de validação ficam em `../artifacts/android-usb/` e
`../research/private/`, ignorados pelo Git. O script
`../scripts/verify-android-capture.py` compara uma captura salva com o resultado
do Windows para a matriz 4×4 observada. Detalhes em
[protocolo observado](../docs/android-protocolo-observado.md).

## Compilar e instalar

### Logs para suporte — 0.6.2

Com o app aberto, reproduza a falha USB e toque em **Salvar log para enviar**
(Visor, conexão ou diálogo Diagnóstico USB). Escolha Downloads e Salvar;
depois use Compartilhar ou anexe o ZIP manualmente. A exportação interrompe
a leitura atual. O nome contém a versão e o horário local.

O registro automático inclui início/pausa do app, conexão/desconexão,
pedidos e estado da permissão, erros capturados e estado USB a cada mudança
ou aproximadamente 10 segundos. O ZIP leva o diagnóstico atual, até dois
arquivos rotativos de eventos e até três sessões recentes, limitadas a 1 MiB
por arquivo. Sessões podem conter medições e imagens e podem estar incompletas.
São logs deste aplicativo; não incluem logcat geral, eventos do kernel ou
dados de outros aplicativos. Não há envio automático.

Validado em emulador: compilação, 14 testes, lint, exportação pelo seletor do
Android, integridade/conteúdo do ZIP e abertura da tela de compartilhamento.

### Instalação em celular/tablet — 0.6.0

Na atualização 0.6.1, o diagnóstico é atualizado a cada segundo enquanto está
aberto. **Autorizar DM5E** solicita somente a permissão USB, sem iniciar leituras;
fica desabilitado quando não há DM5E enumerado, quando já está autorizado ou
durante uma leitura. O diálogo deixa de atualizar ao ser fechado ou ao sair do
app. APK: `../artifacts/android-usb/DM5ESE-0.6.1-usb.apk`.
Verificação: compilação/lint e estado sem dispositivo no emulador; autorização
e enumeração no SM-T575 aguardam acesso ao tablet físico.

Distribuição identificada: `../artifacts/android-usb/DM5ESE-0.6.0-visor-numerico.apk`.
Instale como atualização para preservar as capturas locais. O cabeçalho exibe
`v0.6.0 · visor numérico`; na aba Visor, deixe **Modo imagem (opcional)**
desmarcado. Requer Android 13 ou superior, conforme minSdk 33.

**Diagnóstico USB** está disponível em Visor e no painel de conexão. Mostra
modelo, Android, versão do app, suporte Host declarado, dispositivos enumerados,
VID/PID e permissão. Use **Copiar** para compartilhar o texto. Uma lista vazia
não determina sozinha se a falha é cabo, adaptador, porta ou alimentação.
O app agora declara o filtro de conexão C251:1705 para que o Android possa
oferecer sua abertura ao conectar o instrumento, conforme a
[documentação USB Host](https://developer.android.com/develop/connectivity/usb/host).

Verificado: build, 14 testes e lint; diálogo no emulador com zero dispositivos.
Celular e tablet físicos não estavam disponíveis via ADB nesta verificação;
a causa relatada nesses aparelhos ainda não foi confirmada.

### Segundo visor USB (0.4.0)

**Atualização 0.5.0:** a aba inicia no modo numérico, recebendo espessura via
`MS` e exibindo mm. Valores com indicador U são identificados como retidos.
O modo de imagem abaixo continua opcional. A validação física com acoplamento
e variação de espessura permanece pendente; veja o relatório do teste.

A aba **Visor** recebe a imagem real do LCD pelo comando somente leitura `8Y`.
Toque em **Iniciar visor USB** para uma sessão de dois minutos. Imagens
incompletas são descartadas; a imagem desaparece se a atualização interromper.
O próprio DM5E pode manter a última espessura sem acoplamento. Consulte os
[resultados e limites do teste físico](../docs/dm5e-visor-usb.md).

Pré-requisitos: JDK 17, SDK Android 35 e `ANDROID_HOME` configurado.

```powershell
cd android-usb-probe
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
cd ..
.\scripts\start-android-usb.ps1 -Install
```

O build exporta automaticamente o APK para
`../artifacts/android-usb/DM5ESE-v<versionName>.apk` (atual: `DM5ESE-v0.6.1.apk`).
Use sempre esse arquivo versionado para distribuição. A versão do nome vem do
mesmo `versionName` incorporado ao app. Ao publicar uma nova versão, atualize
`versionName` e incremente `versionCode` em `app/build.gradle`.

APK interno do build: `app/build/outputs/apk/debug/app-debug.apk`. Para reabrir sem reinstalar:
`scripts/start-android-usb.ps1`. O script usa somente o AVD dedicado, porta 5580.
Não substitui o driver COM do Windows. Não use outro leitor simultaneamente.

## Preparar o emulador em outra instalação

Crie `DM5ESE_USB_API35` com `system-images;android-35;google_apis;x86_64`
(Google APIs, sem Play Store). Inicie com `-writable-system -no-snapshot` e
`-usb-passthrough vendorid=0xc251,productid=0x1705`.

Se faltar USB Host, use apenas nesse AVD `adb -s emulator-5580 root`,
`disable-verity`, `remount`, reiniciando quando solicitado. Repita root/remount,
envie `emulator/android.hardware.usb.host.xml` para
`/vendor/etc/permissions/android.hardware.usb.host.xml`, aplique modo 644 e
`restorecon`, e reinicie. Isso prepara somente a imagem virtual de teste.
Confirme `pm list features` e `dumpsys usb`. O overlay pode precisar ser
reaplicado se a imagem for recriada; o script verifica se o recurso está presente.

## Referências

- [USB Host Android](https://developer.android.com/develop/connectivity/usb/host)
- [Notas do emulador](https://developer.android.com/studio/releases/emulator)
- [Estudo e estratégia](../docs/android-viabilidade-estrategia.md)
