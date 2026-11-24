# Alterações

Formato: data, versão, o que mudou, ficheiros e impacto noutros projetos.
As entradas marcadas **[integração]** afetam o Motor comercial e estão também em
`CHANGELOG-integracao.md` desse projeto.

## 0.2.0 · 2026-11-24 · Chamadas pedidas pelo PC **[integração]**

**Novo**
- API local no PC (`127.0.0.1`, token em `api.json`): `GET /api/status`, `POST /api/dial`,
  `GET /api/calls`. Contrato: `docs/INTEGRACAO_MOTOR_COMERCIAL.md`.
- Protocolo PC ↔ telemóvel: mensagens `dial`, `dial_ack` e `call_state`.
- Android: o número enviado pelo PC aparece no marcador (ou numa notificação que o abre); o
  utilizador carrega em ligar. Estado da chamada (a tocar, em curso, terminada) enviado ao PC.
- Android: secção «6 · Chamadas pedidas pelo PC» com as permissões necessárias.
- Simulador (`tools/fake_phone.py`) responde a `dial` e simula uma chamada de 2 s.

**Ficheiros**
- PC: `desktop/sync_stonedge/localapi.py` (novo), `app.py`, `client.py`, `__init__.py`.
- Android: `Dialer.kt`, `CallMonitor.kt` (novos), `SyncServer.kt`, `SyncCore.kt`,
  `ServerService.kt`, `MainActivity.kt`, `SyncApp.kt`, `AndroidManifest.xml`.
- `scripts/windows/instalar_android.ps1`: espera pelo telemóvel, diagnóstico claro, novas permissões.

**Impacto no Motor comercial**: passa a poder usar a API local (módulo `motor/chamadas.py`).
Requer Sync_Stonedge ≥ 0.2.0 no PC e no telemóvel.

## 0.1.0 · 2026-11-24 · Primeira versão

- Notificações do telemóvel no PC (Windows 11), emparelhamento por PIN, TLS com certificado fixado,
  histórico, escolha por app, instaladores e compilação automática.
