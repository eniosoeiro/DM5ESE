# UltraMATE Lite: erro 6 na inicialização

## Diagnóstico neste computador

UltraMATE Lite 2.60.28, instalado em `C:\Program Files (x86)\UltraMATE Lite`.
O erro foi reproduzido com o depurador x86 do Windows SDK. A exceção
`C000008F`, com erro VB6 `800A0006`, ocorre no endereço `007A3D7B`, durante
a conversão de um número de ponto flutuante para um inteiro de 16 bits.
O valor observado foi **38400**, correspondente a 2560 pixels × 15 twips/pixel.
O código seguinte armazena o resultado em uma variável de 16 bits, cujo
limite positivo é 32767. O monitor principal estava em 2560×1080.

## Contorno implementado

`artifacts/UltraMATE/Abrir-UltraMATE.exe`, compilado de
`scripts/ultramate-launcher.cs`, inicia o aplicativo GE original.

1. Confere se já existe uma instância aberta.
2. Guarda o modo atual do monitor principal.
3. Testa e aplica temporariamente 1920×1080, se necessário.
4. Inicia o UltraMATE e aguarda a janela principal, com limite de 30 segundos.
5. Restaura o modo anterior em `finally`, inclusive se a abertura falhar.

A tela pode piscar e outras janelas podem se reposicionar nessa troca.
Nenhuma configuração de resolução é gravada permanentemente. O executável
GE, as DLLs, a licença e o driver USB não são alterados. O registro de cada
abertura fica em `ultima-abertura.log`, junto ao iniciador.

O contorno foi validado neste computador: a janela `UltraMATE - Lite`
abriu em 1920×1080 e permaneceu aberta após o retorno a 2560×1080.
Isso verifica a inicialização, não todas as funções do programa nem uma
transferência real do instrumento. O modo de compatibilidade `640X480`
testado isoladamente não resolveu; não foi persistido no Registro.

## Compilar

```powershell
& 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe' /nologo /target:winexe /platform:x64 /reference:System.Windows.Forms.dll /out:artifacts\UltraMATE\Abrir-UltraMATE.exe scripts\ultramate-launcher.cs
```

## Documentação do DM5E

O [guia de instalação USB](https://www.scribd.com/document/503099177/DM5EUSBDriverInstall)
explica conectar o instrumento ligado com mini-USB e verificar a porta
`DM5E USB VCom Port`. Há uma cópia local em
`C:\Program Files (x86)\GE Inspection Technologies\DM5EExcelMacro\DM5EUSBDriverInstall.pdf`.
As telas do guia são de uma versão antiga do Windows.

O [manual oficial](https://dam.bakerhughes.com/m/642cbb4988da786f/original/Krautkramer-DM5E-Operating-Manual_EN.pdf),
capítulo 6, páginas impressas 53–54, confirma COM virtual por mini-USB e
download de arquivos por UltraMATE, UltraMATE Lite ou macro Excel.
Página impressa 1: USB não alimenta nem carrega o instrumento.

Na verificação de dispositivos presentes, apareceu apenas COM1 (ACPI),
sem `USB\VID_C251&PID_1705` do DM5E. Resolver a abertura do UltraMATE
não resolve essa ausência de reconhecimento USB.
