# Integração encontrada no pacote original GE

Inspeção local em 15/09/2026. Nenhuma macro do Excel foi executada.

## Fontes

- Planilha: `C:\Program Files (x86)\GE Inspection Technologies\DM5EExcelMacro\DM5EExcelMacro.xls`
- SHA-256: `df4c96005a28be5bff1c34c1c8a2b5b17c8220e65a937f00feb30af3444b6ff1`
- Instalador disponível: `E:\DM5E Excel Macro\DM5EExcelMacro.msi`
- Driver USB: `E:\USB Driver\dm5e-vcom.inf`
- Componente instalado: `C:\Windows\SysWOW64\DM5EFileSrv.dll`, versão 1.1.1.0.

## Resultado

A macro delega o transporte e o download a um objeto COM. O VBA contém a apresentação das leituras e a sequência de uso desse objeto, mas não os comandos seriais de baixo nível.

O registro de 32 bits `HKLM\SOFTWARE\WOW6432Node\Krautkramer\InstDrivers\Installed` identifica:

- Nome: DM5E.
- ProgID: `DM5EFileSrv.DM5EInstDrv.1`.
- CLSID: `{8F0DE8A9-EBB5-4FCA-8102-D3311B942360}`.
- TypeLib: `{EFB5A822-67CD-49AF-9D63-C08921B0954D}`.
- DLL COM em processo, modelo Apartment.

Foi possível criar o objeto no Windows PowerShell de **32 bits** e enumerar seus membros. O Excel instalado é x64. A integração Tauri usa um processo auxiliar PowerShell de 32 bits. O primeiro teste físico listou 17 arquivos e recebeu 16 posições do arquivo `01`, com o DM5E reconhecido na COM9.

A versão de teste implementa conexão automática GE, listagem, download, validação da estrutura e exportação CSV/JSON. O processo auxiliar é encerrado em caso de cancelamento ou após 180 segundos. Os testes de estrutura, estados e CSV usam dados sintéticos e não comprovam a transferência física.

## Sequência da macro

1. `CreateObject` com o ProgID registrado.
2. Verificar `AutoConnectAvail()` e chamar `AutoConnect()`.
3. Se `IsDirAvail()`, verificar `TestConnection()` e chamar `UpdateDirectory()`.
4. Enumerar `NumberDirFiles()` e `DirectoryEntry(index)`, com índice iniciando em zero.
5. Chamar `ReceiveFile(index)` para o arquivo selecionado.
6. Consultar estrutura e posições, `Reading(index)` e `ReadingStatus(index)`.

Métodos e propriedades confirmados pela enumeração COM incluem `Port`, `Baud`, `Data`, `ParitySetting`, `Stop`, `NumOfReadings`, `FileTypeNum`, `LevelType`, `AStart`, `AEnd`, `BStart`, `BEnd`, `ColLabelsAlpha`, `NameLength` e `PointName`.

Os valores de retorno, parâmetros e comportamento com desconexão precisam ser validados. O transporte do aplicativo deve ter timeout e lista explícita de operações permitidas. Não expor métodos arbitrários: o objeto também fornece exclusão de arquivos e envio de configurações.

## Unidades e estados

O VBA usa `ReadingStatus` para distinguir:

- `0x20`: posição vazia (`EMPTY`).
- `0x80`: obstrução (`OBSTR`).
- Bit `0x01`: polegadas quando definido, milímetros quando zero.
- `0x40`: captura de mínimo; `0x02`: leitura antiga.

A exportação deve preservar posição, estado e unidade. Posições vazias ou obstruídas não devem virar espessura zero. A macro calcula a contagem com a estrutura em até quatro níveis; comparar esse cálculo com `NumOfReadings` antes de usar a propriedade como autoridade.

## Reconhecimento USB

O INF original associa `USB\VID_C251&PID_1705` a `DM5E USB VCom Port` e ao serviço Windows `usbser.sys`, com seções x86 e amd64. Isso identifica o hardware esperado; não prova que o driver antigo pode ser instalado sem ajustes no Windows atual.

Após a troca/reconexão do cabo, o dispositivo apareceu como `USB Serial Device (COM9)`, com identificador `USB\VID_C251&PID_1705\DEMO00000000`. O nome genérico do Windows não impede a comunicação. A DLL informa `Port=1`, que não corresponde ao número COM físico. A ponte exige um único DM5E presente e obtém o número da porta pelo PnP do Windows; não usa `Port` da DLL para validar COM9. O significado exato desse campo GE permanece sem documentação.

## Reproduzir a inspeção

```powershell
& C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe -NoProfile -NonInteractive -File scripts\inspect-ge-component.ps1
```

Extração estática: `scripts/extract-vba.py`, com `oletools==0.60.2` instalado em `.venv`. Os módulos extraídos ficam em `research/private/vba`, ignorados pelo Git. Não redistribuir a DLL ou o instalador junto com o aplicativo sem verificar seus termos de distribuição.
