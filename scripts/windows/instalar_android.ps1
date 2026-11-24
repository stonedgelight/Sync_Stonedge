# Sync_Stonedge - instala a app Android por ADB e concede as permissoes necessarias.
param([string]$Apk = "")
$ErrorActionPreference = "Continue"
$pkg = "pt.andrecarmo.syncstonedge"
$url = "https://github.com/stonedgelight/Sync_Stonedge/releases/latest/download/Sync_Stonedge.apk"
function Find-Adb {
  $c = (Get-Command adb -ErrorAction SilentlyContinue).Source
  if ($c) { return $c }
  $cands = @("$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe", "C:\platform-tools\adb.exe",
             "C:\adb\adb.exe", "$env:USERPROFILE\platform-tools\adb.exe", "$env:USERPROFILE\scoop\shims\adb.exe")
  return $cands | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
}
# Corre o adb via cmd para evitar os falsos erros "NativeCommandError" do PowerShell 5.1
function Adb { & $script:adb @args 2>&1 | ForEach-Object { "$_" } }

function Wait-Device {
  Write-Host ""
  Write-Host "A procurar o telemovel por USB..."
  $shown = $false
  for ($i = 0; $i -lt 90; $i++) {
    $s = ((Adb get-state) | Out-String).Trim()
    if ($s -eq "device") { Write-Host "Telemovel ligado." -ForegroundColor Green; return $true }
    if ($s -match "unauthorized") {
      Write-Host "No telemovel: aceita 'Permitir depuracao USB' (marca 'Permitir sempre')." -ForegroundColor Yellow
    } elseif (-not $shown) {
      $shown = $true
      Write-Host "O Windows nao ve o telemovel por ADB. Confirma no telemovel:" -ForegroundColor Yellow
      Write-Host "  1. Definicoes > Definicoes adicionais > Opcoes de programador: LIGADO"
      Write-Host "     (se a entrada desapareceu: Definicoes > Acerca do telefone > toca 7x na versao do HyperOS)"
      Write-Host "  2. Nas Opcoes de programador: 'Depuracao USB' LIGADA"
      Write-Host "     e 'Instalar via USB' LIGADA (o Xiaomi exige-a para instalar apps)"
      Write-Host "  3. Ao ligar o cabo, escolhe 'Transferencia de ficheiros' na notificacao USB"
      Write-Host "  4. Aceita o pedido 'Permitir depuracao USB' que aparece no ecra"
      $pnp = Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue |
             Where-Object { $_.FriendlyName -match "ADB|Android|Xiaomi|Redmi|MTP|Composite ADB" }
      if ($pnp) { Write-Host "  (o Windows ve por USB: $($pnp.FriendlyName -join '; '))" }
      else { Write-Host "  (o Windows nao ve nenhum dispositivo Android por USB: cabo so de carga ou modo 'So carregar'?)" }
      Write-Host "Fico a espera ate 3 minutos..."
    }
    Start-Sleep -Seconds 2
  }
  return $false
}

$script:adb = Find-Adb
if (-not $script:adb) { Write-Host "adb nao encontrado. Instala as Android platform-tools." -ForegroundColor Red; Read-Host "Enter"; exit 1 }
Write-Host "adb: $script:adb"
if (-not (Wait-Device)) { Write-Host "Telemovel nao detetado. Corrige os pontos acima e volta a correr." -ForegroundColor Red; Read-Host "Enter"; exit 1 }

$diag = Join-Path $PSScriptRoot "diagnostico\recolha_adb.ps1"
if (Test-Path $diag) { Write-Host "A recolher diagnostico (so leitura)..."; & $diag -NoWait }

if (-not $Apk) {
  $Apk = Join-Path $env:TEMP "Sync_Stonedge.apk"
  Write-Host "A transferir: $url"
  try { Invoke-WebRequest -Uri $url -OutFile $Apk -UseBasicParsing -ErrorAction Stop }
  catch { Write-Host "Falhou a transferencia: $_" -ForegroundColor Red; Read-Host "Enter"; exit 1 }
}

Write-Host "A instalar (se o telemovel pedir confirmacao, aceita)..."
$r = (Adb install -r $Apk) | Out-String
Write-Host $r
if ($r -notmatch "Success") {
  if ($r -match "INSTALL_FAILED_USER_RESTRICTED") {
    Write-Host "O Xiaomi bloqueou: ativa 'Instalar via USB' nas Opcoes de programador e volta a correr." -ForegroundColor Red
  }
  Read-Host "Enter"; exit 1
}

Write-Host "A conceder permissoes..."
Adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS | Out-Null
Adb shell cmd notification allow_listener "$pkg/$pkg.SyncListenerService"
Adb shell dumpsys deviceidle whitelist "+$pkg"
Adb shell cmd appops set $pkg RUN_IN_BACKGROUND allow
Adb shell cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow
# v0.2: chamadas pedidas pelo PC
Adb shell pm grant $pkg android.permission.READ_PHONE_STATE
Adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow
Adb shell appops set $pkg USE_FULL_SCREEN_INTENT allow
Adb shell appops set $pkg 10021 allow
Adb shell am start -n "$pkg/$pkg.MainActivity" | Out-Null
Write-Host ("Listener ativo: " + ((Adb shell settings get secure enabled_notification_listeners) | Out-String).Contains($pkg))

Write-Host ""
Write-Host "Concluido. No telemovel, na app Sync_Stonedge: toca em 'Arranque automatico (Xiaomi)' e ativa." -ForegroundColor Green
Read-Host "Enter para fechar"
