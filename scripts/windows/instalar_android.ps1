# Sync_Stonedge - instala a app Android por ADB e concede as permissões necessárias.
# Uso: duplo clique em instalar_android.bat (telemóvel ligado por cabo, depuração USB ativa).
#      Opcional: -Apk C:\caminho\Sync_Stonedge.apk para usar um ficheiro local.
param([string]$Apk = "")

$ErrorActionPreference = "Continue"   # o adb escreve avisos no stderr
$pkg = "pt.andrecarmo.syncstonedge"
$url = "https://github.com/stonedgelight/Sync_Stonedge/releases/latest/download/Sync_Stonedge.apk"

function Find-Adb {
  $c = (Get-Command adb -ErrorAction SilentlyContinue).Source
  if ($c) { return $c }
  $cands = @("$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe", "C:\platform-tools\adb.exe",
             "C:\adb\adb.exe", "$env:USERPROFILE\platform-tools\adb.exe", "$env:USERPROFILE\scoop\shims\adb.exe")
  $cands += Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter adb.exe -ErrorAction SilentlyContinue |
            ForEach-Object FullName
  return $cands | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
}

$adb = Find-Adb
if (-not $adb) { Write-Host "adb não encontrado. Instala as Android platform-tools." -ForegroundColor Red; Read-Host "Enter"; exit 1 }
Write-Host "adb: $adb"

$state = (& $adb get-state 2>&1)
if ($state -ne "device") { Write-Host "Telemóvel não ligado/autorizado ($state)." -ForegroundColor Red; Read-Host "Enter"; exit 1 }

if (-not $Apk) {
  $Apk = Join-Path $env:TEMP "Sync_Stonedge.apk"
  Write-Host "A transferir a versão mais recente: $url"
  Invoke-WebRequest -Uri $url -OutFile $Apk -UseBasicParsing -ErrorAction Stop
}

Write-Host "A instalar $Apk ..."
& $adb install -r $Apk
if ($LASTEXITCODE -ne 0) { Write-Host "A instalação falhou." -ForegroundColor Red; Read-Host "Enter"; exit 1 }

Write-Host "A conceder permissões..."
& $adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS
& $adb shell cmd notification allow_listener "$pkg/$pkg.SyncListenerService"
& $adb shell dumpsys deviceidle whitelist +$pkg
& $adb shell cmd appops set $pkg RUN_IN_BACKGROUND allow
& $adb shell cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow
& $adb shell am start -n "$pkg/$pkg.MainActivity" | Out-Null

Write-Host ""
Write-Host "Concluído. Falta só, no telemóvel: Definições > Apps > Sync_Stonedge >" -ForegroundColor Green
Write-Host "  - Arranque automático: ativar" -ForegroundColor Green
Write-Host "  - Poupança de bateria: Sem restrições" -ForegroundColor Green
Write-Host "(a app tem botões que abrem estes ecrãs)"
Read-Host "Enter para fechar"
