# Streams the Roku BrightScript debug console (telnet port 8085) to the terminal and
# to out\console.log. No password needed. Stops after -Seconds, or on Ctrl+C.
param(
    [Parameter(Mandatory = $true)][string]$RokuIp,
    [int]$Seconds = 0,
    [string]$LogFile = (Join-Path $PSScriptRoot 'out\console.log')
)

New-Item -ItemType Directory -Force (Split-Path $LogFile) | Out-Null
$client = New-Object System.Net.Sockets.TcpClient
$client.Connect($RokuIp, 8085)
$stream = $client.GetStream()
$reader = New-Object System.IO.StreamReader($stream)
$writer = New-Object System.IO.StreamWriter($LogFile, $true)
$writer.AutoFlush = $true
$deadline = if ($Seconds -gt 0) { (Get-Date).AddSeconds($Seconds) } else { [datetime]::MaxValue }
Write-Host "--- Roku debug console ${RokuIp}:8085 (logging to $LogFile) ---"
try {
    while ((Get-Date) -lt $deadline -and $client.Connected) {
        if ($stream.DataAvailable) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { break }
            Write-Host $line
            $writer.WriteLine($line)
        } else {
            Start-Sleep -Milliseconds 100
        }
    }
} finally {
    $writer.Dispose()
    $client.Close()
}
