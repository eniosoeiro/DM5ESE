$ErrorActionPreference = 'Stop'
$driver = New-Object -ComObject DM5EFileSrv.DM5EInstDrv.1
try {
    # Only the fresh, local document state is inspected. No instrument I/O.
    $driver.CleanVDoc()
    $kind = $driver.GetType().InvokeMember('LevelType', [Reflection.BindingFlags]::GetProperty, $null, $driver, @([int]1))
    [pscustomobject]@{ driver = $driver.DriverName(); emptyCount = $driver.NumOfReadings; levelType = $kind; baud = $driver.Baud; data = $driver.Data; parity = $driver.ParitySetting; stop = $driver.Stop; validPorts = @(for ($i=0; $i -lt $driver.NumberValidPorts(); $i++) { $driver.ValidPort($i) }); baudRates = @(for ($i=0; $i -lt $driver.NumberBaudRates(); $i++) { $driver.BaudRate($i) }) } | ConvertTo-Json
} finally {
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($driver)
}
