# DM5ESE

Aplicativo Windows em Tauri 2 para capturar arquivos do datalogger GE / Waygate DM5E e exportar espessuras. Versão experimental para teste com o instrumento.

## Testar com o medidor

1. Abra `artifacts/DM5ESE/DM5ESE.exe` (versão de teste compilada).
2. Ligue o DM5E e conecte o cabo de dados mini-USB.
3. Clique em **Atualizar portas**. A identificação esperada é `C251:1705`.
4. Deixe **Automática · USB DM5E** e clique em **Conectar e listar arquivos**.
5. Selecione um arquivo e clique em **Capturar arquivo selecionado**.
6. Confira posições, valores e unidades na tabela. Use **Exportar CSV** ou **Salvar JSON**.

A conexão exige exatamente um DM5E USB identificado por `C251:1705`. A seleção manual deve corresponder à porta desse dispositivo. O componente GE negocia a conexão como na macro original. Sua propriedade `Port` não representa o número COM do Windows: a porta exibida é obtida da identificação USB do Windows. O programa lê arquivos já salvos no datalogger; não é um monitor de espessura ao vivo.

Cada operação tem limite de 180 segundos e pode ser cancelada. Uma leitura incompleta ou estruturalmente inconsistente é recusada. A última captura concluída permanece disponível até ser substituída por outra captura bem-sucedida. Exporte os dados antes de fechar o aplicativo; não há histórico persistente automático.

## Dependências do computador de teste

- Windows 64 bits com WebView2 e Windows PowerShell de 32 bits.
- Pacote **DM5EExcelMacro** instalado e componente `DM5EFileSrv.DM5EInstDrv.1` registrado. Neste computador está em `C:\Windows\SysWOW64\DM5EFileSrv.dll`.
- Driver USB do DM5E e conexão reconhecida pelo Windows.

Excel e UltraMATE não são necessários para executar o aplicativo. O componente proprietário da GE não é incluído no executável. A lógica de captura está em `src-tauri/ge-bridge.ps1`, incorporada ao binário; não é preciso copiar o script ao distribuir o executável.

## Exportação

A captura aparece como planilha: arquivos com posições como `1A`, `1B`, `2A`
formam uma matriz de linhas e colunas. Outros formatos mantêm as posições em
uma grade com espessura, unidade e estado. Os dados são somente leitura.
Use **Copiar planilha** para copiar todas as páginas com cabeçalhos, ou selecione
células arrastando o mouse / Shift + clique e use **Ctrl+C** ou **Copiar seleção**.
Cole no Excel com Ctrl+V: colunas são separadas por tabulação e números usam
vírgula decimal. Células vazias permanecem vazias; obstruções aparecem como OBSTR.

CSV UTF-8 com BOM, separador `;` e vírgula decimal, com arquivo, horário UTC da captura, porta, série informada pelo componente, índice (zero-based), posição, espessura, unidade, estado e status GE original. O horário corresponde à captura no computador, não ao momento da medição no aparelho.

Posições vazias (`EMPTY`) ou obstruídas (`OBSTR`) têm valor ausente. Unidades `mm` e `in` são preservadas sem conversão. O JSON inclui a estrutura do arquivo e o código retornado por `ReceiveFile` para diagnóstico. Valores exibidos têm até seis casas decimais; as exportações usam o valor recebido da DLL.

## Desenvolver e compilar

Instale Node.js, Rust MSVC, ferramentas C++ do Visual Studio e Windows SDK. [Pré-requisitos Tauri](https://v2.tauri.app/start/prerequisites/).

```powershell
npm install
npm run tauri dev
```

Para apenas visualizar a interface: `npm run dev`. A prévia no navegador desabilita as operações nativas.

```powershell
npm run build
npm run test:ui
cargo test --manifest-path src-tauri/Cargo.toml
cargo test --manifest-path src-tauri/Cargo.toml installed_component_probe -- --ignored
npm run tauri -- build --debug --no-bundle
```

O teste de integração marcado como ignorado por padrão exige a DLL GE instalada. Os testes de interface usam respostas simuladas apenas nos testes. Eles não comprovam comunicação com o instrumento.

Neste computador, use o ambiente `VC\Auxiliary\Build\vcvars64.bat` da instalação `C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools`. A instalação Insiders selecionada automaticamente não encontra `msvcrt.lib`. Se necessário, acrescente `$env:USERPROFILE\.cargo\bin` ao PATH da sessão.

## Limitações e diagnóstico

O instrumento foi reconhecido na COM9, com USB `C251:1705`. A primeira leitura física listou 17 arquivos e recebeu 16 posições do arquivo `01`, incluindo valores próximos de 5,12 mm e 4,86 mm. A propriedade GE `Port` retornou 1 mesmo com o dispositivo na COM9; a integração foi ajustada para identificar a porta pelo Windows. Os demais arquivos e casos de desconexão ainda precisam ser conferidos no instrumento.

O aplicativo não oferece apagar arquivos, enviar configurações, calibrar ou atualizar firmware. O fluxo chama as funções de conexão e download identificadas na macro original. A contagem retornada pela DLL deve coincidir com as dimensões da estrutura, caso contrário a captura é rejeitada e o registro da sessão mostra a diferença.

Fontes locais e detalhes técnicos em [docs/ge-integration.md](docs/ge-integration.md). O [manual oficial do DM5E](https://dam.bakerhughes.com/m/642cbb4988da786f/original/Krautkramer-DM5E-Operating-Manual_EN.pdf) descreve a conexão mini-USB/COM e alimentação por pilhas.
