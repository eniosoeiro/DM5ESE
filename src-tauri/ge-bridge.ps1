# Embedded by the Rust application. Only read operations are exposed.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$driver = $null
function Indexed([string]$name, [int]$index) {
    return $driver.GetType().InvokeMember($name, [Reflection.BindingFlags]::GetProperty, $null, $driver, @($index))
}
try {
    if ([Environment]::Is64BitProcess) { throw 'O componente GE requer um processo de 32 bits.' }
    $request = $env:DM5E_REQUEST | ConvertFrom-Json
    if ($request.action -notin @('probe', 'list', 'read')) { throw 'Operacao nao permitida.' }
    $driver = New-Object -ComObject DM5EFileSrv.DM5EInstDrv.1
    if ($request.action -eq 'probe') {
        $result = @{ driver = $driver.DriverName(); bits = 32; ready = $true }
    } else {
        # Port on this GE component is not the Windows COM number (it reports
        # 1 while the instrument is on COM9). Bind to the unique USB identity.
        $devices = @(Get-CimInstance Win32_PnPEntity | Where-Object {
            $_.PNPDeviceID -like 'USB\VID_C251&PID_1705\*' -and $_.ConfigManagerErrorCode -eq 0
        })
        if ($devices.Count -ne 1) { throw 'Conecte exatamente um DM5E USB para identificar a porta com seguranca.' }
        if ($devices[0].Name -notmatch '\(COM(\d+)\)') { throw 'O DM5E ainda nao possui uma porta COM reconhecida.' }
        $windowsPort = [int]$Matches[1]
        if ([int]$request.port -gt 0 -and [int]$request.port -ne $windowsPort) { throw "O DM5E esta na COM$windowsPort. Atualize e selecione essa porta." }
        # Follow the original macro: GE negotiates its serial configuration.
        # Validate the Windows port above; do not interpret GE's Port as COMn.
        if (-not $driver.AutoConnectAvail()) { throw 'O componente nao oferece conexao automatica.' }
        if (-not $driver.AutoConnect()) { throw 'DM5E nao respondeu a conexao automatica. Confira cabo, porta e driver USB.' }
        if (-not $driver.TestConnection()) { throw 'A conexao com o instrumento foi interrompida.' }
        if (-not $driver.IsDirAvail()) { throw 'Listagem do datalogger indisponivel neste instrumento.' }
        $driver.UpdateDirectory()
        $count = [int]$driver.NumberDirFiles()
        if ($count -lt 0 -or $count -gt 50000) { throw 'Quantidade de arquivos invalida.' }
        $files = @(
            for ($i = 0; $i -lt $count; $i++) {
                @{ index = $i; name = ([string]$driver.DirectoryEntry($i)).Trim() }
            }
        )
        $connection = @{ port = $windowsPort; baud = [int]$driver.Baud; data = [int]$driver.Data; parity = [int]$driver.ParitySetting; stop = [int]$driver.Stop; serial = [string]$driver.SerialNumber }
        if ($request.action -eq 'list') {
            $result = @{ connection = $connection; files = $files }
        } else {
            $index = [int]$request.index
            if ($index -lt 0 -or $index -ge $count) { throw 'Arquivo nao existe mais. Atualize a lista.' }
            if ($files[$index].name -cne [string]$request.name) { throw 'A lista de arquivos mudou. Atualize antes de capturar.' }
            if ($request.serial -and $connection.serial -cne $request.serial) { throw 'O instrumento conectado mudou. Atualize a lista.' }
            $driver.CleanVDoc()
            $receiveCode = [int]$driver.ReceiveFile($index)
            $n = [int]$driver.NumOfReadings
            if ($n -le 0 -or $n -gt 50000) { throw "Nenhum conjunto valido foi recebido (retorno GE: $receiveCode, quantidade: $n)." }
            if (-not $driver.TestConnection()) { throw 'Conexao perdida durante a captura. Os dados parciais foram descartados.' }
            $levels = @(
                for ($l = 1; $l -le 4; $l++) {
                    $kind = [int](Indexed 'LevelType' $l)
                    $aStart = [int](Indexed 'AStart' $l)
                    $aEnd = [int](Indexed 'AEnd' $l)
                    $bStart = [int](Indexed 'BStart' $l)
                    $bEnd = [int](Indexed 'BEnd' $l)
                    if ($aEnd -lt $aStart -or $bEnd -lt $bStart -or ($aEnd - $aStart) -ge 50000 -or ($bEnd - $bStart) -ge 50000) { throw 'Estrutura de arquivo invalida.' }
                    $names = @()
                    if ($kind -eq 3) {
                        $names = @(for ($p = 0; $p -le ($aEnd - $aStart); $p++) {
                            [string]$driver.GetType().InvokeMember('PointName', [Reflection.BindingFlags]::GetProperty, $null, $driver, @([int]$l, [int]$p))
                        })
                    }
                    @{ kind = $kind; aStart = $aStart; aEnd = $aEnd; bStart = $bStart; bEnd = $bEnd; columnsAlpha = [bool](Indexed 'ColLabelsAlpha' $l); names = $names }
                }
            )
            $readings = @(
                for ($i = 0; $i -lt $n; $i++) {
                    $status = [int](Indexed 'ReadingStatus' $i)
                    $value = $null
                    if (($status -band 0xA0) -eq 0) {
                        $value = [double](Indexed 'Reading' $i)
                        if ([double]::IsNaN($value) -or [double]::IsInfinity($value)) { throw "Leitura invalida na posicao $i." }
                    }
                    @{ index = $i; value = $value; status = $status }
                }
            )
            $result = @{ connection = $connection; file = $files[$index]; capturedAt = [DateTime]::UtcNow.ToString('o'); fileType = [int]$driver.FileTypeNum; receiveCode = $receiveCode; levels = $levels; readings = $readings }
        }
    }
    $json = @{ ok = $true; data = $result } | ConvertTo-Json -Depth 12 -Compress
    [Console]::WriteLine($json)
} catch {
    [Console]::WriteLine((@{ ok = $false; error = $_.Exception.Message } | ConvertTo-Json -Compress))
    exit 1
} finally {
    if ($null -ne $driver) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($driver) }
}
