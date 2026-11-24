# Sync_Stonedge - instala a app de PC no Windows (sem precisar de Python).
# Transfere o executável da release mais recente para %LOCALAPPDATA%\Programs\Sync_Stonedge,
# cria um atalho no menu Iniciar e arranca a app.
$ErrorActionPreference = "Stop"
$url = "https://github.com/stonedgelight/Sync_Stonedge/releases/latest/download/Sync_Stonedge.exe"
$dir = Join-Path $env:LOCALAPPDATA "Programs\Sync_Stonedge"
$exe = Join-Path $dir "Sync_Stonedge.exe"

New-Item -ItemType Directory -Force -Path $dir | Out-Null
Get-Process Sync_Stonedge -ErrorAction SilentlyContinue | Stop-Process -Force
Write-Host "A transferir $url"
Invoke-WebRequest -Uri $url -OutFile $exe -UseBasicParsing
Unblock-File $exe

$lnk = Join-Path ([Environment]::GetFolderPath("Programs")) "Sync_Stonedge.lnk"
$sh = New-Object -ComObject WScript.Shell
$s = $sh.CreateShortcut($lnk)
$s.TargetPath = $exe
$s.WorkingDirectory = $dir
$s.Description = "Notificações do telemóvel no PC"
$s.Save()

Start-Process $exe
Write-Host "Instalado em $exe. O ícone aparece junto ao relógio (pode estar nos ícones ocultos)." -ForegroundColor Green
Read-Host "Enter para fechar"
