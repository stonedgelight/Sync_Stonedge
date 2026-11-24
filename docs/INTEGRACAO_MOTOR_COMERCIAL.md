# Integração Sync_Stonedge ↔ Motor comercial (chamadas)

Documento de contrato entre os dois projetos. Existe uma cópia idêntica em cada um:

- Sync_Stonedge: `docs/INTEGRACAO_MOTOR_COMERCIAL.md`
- Motor comercial (prospeção de leads v2): `docs/INTEGRACAO_SYNC_STONEDGE.md`

Qualquer alteração a este contrato tem de ser feita nas duas cópias e registada nos dois
ficheiros de alterações (`CHANGELOG.md` no Sync_Stonedge, `CHANGELOG-integracao.md` no Motor).

Versão do contrato: **1** (Sync_Stonedge 0.2.0, Motor com o módulo `motor/chamadas.py`).

## 1. Papéis

| Quem | Faz | Não faz |
|---|---|---|
| Motor comercial | Decide quem ligar (fila), mostra o lead, guarda o resultado na base de dados | Não fala com o telemóvel diretamente |
| Sync_Stonedge (PC) | Expõe a API local, encaminha o número para o telemóvel, guarda os estados da chamada | Não conhece leads nem a base de dados do Motor |
| Sync_Stonedge (Android) | Põe o número no marcador e informa o estado da chamada | Nunca liga sozinho: o utilizador carrega em ligar |

## 2. Descoberta da API local

O Sync_Stonedge (PC), ao arrancar, escreve o ficheiro:

- Windows: `%APPDATA%\Sync_Stonedge\api.json`
- Linux: `~/.config/Sync_Stonedge/api.json`
- Pode ser substituído pela variável de ambiente `SYNC_STONEDGE_API` (usado em testes).

```json
{"port": 47802, "token": "<aleatório, muda a cada arranque>", "pid": 1234, "api_version": 1}
```

A API escuta só em `127.0.0.1`. Todos os pedidos levam o cabeçalho `X-Sync-Token: <token>`;
sem ele a resposta é `401`.

## 3. Endpoints

### `GET /api/status`
```json
{"ok": true, "connected": true, "device": "Xiaomi 2201116SG", "call_state": "idle",
 "api_version": 1, "app": "0.2.0"}
```
`connected` é falso quando o telemóvel não está emparelhado e ligado.

### `POST /api/dial`
Pedido: `{"number": "+351223456789", "name": "Nome do negócio", "lead_id": 123}`
Resposta (até ~6 s, espera pela confirmação do telemóvel):
```json
{"ok": true, "req": "c9ddf157d2f5", "via": "dialer", "seq": 7, "error": null}
```
- `via = "dialer"`: o marcador abriu com o número.
- `via = "notification"`: o Android não deixou abrir o marcador a partir de segundo plano; ficou
  uma notificação no telemóvel que abre o marcador ao tocar.
- `ok = false` + `error`: telemóvel desligado, número inválido ou app Android antiga (< 0.2).

### `GET /api/calls?since=N`
Eventos com `seq > N` (memória dos últimos 300, perde-se ao reiniciar o PC):
```json
{"ok": true, "last_seq": 9, "events": [
  {"seq": 7, "type": "dial", "ts": 1795525877721, "number": "+351223456789", "name": "...", "lead_id": 123, "via": "dialer"},
  {"seq": 8, "type": "call_state", "ts": 1795525878722, "state": "offhook", "phone_ts": 1795525878722},
  {"seq": 9, "type": "call_state", "ts": 1795525880725, "state": "idle", "phone_ts": 1795525880722}
]}
```
`ts` em milissegundos (relógio do PC). `state`: `ringing` (chamada a entrar), `offhook` (a marcar
ou em conversa), `idle` (sem chamada). Numa chamada feita, a duração calculada vai do `offhook`
ao `idle` e inclui o tempo a tocar.

## 4. Protocolo PC ↔ telemóvel (WebSocket seguro, já existente)

Mensagens acrescentadas na versão 0.2:

| Sentido | Tipo | Campos |
|---|---|---|
| PC → tel. | `dial` | `req`, `number`, `name`, `lead_id` |
| tel. → PC | `dial_ack` | `req`, `ok`, `via`, `error` |
| tel. → PC | `call_state` | `state`, `ts` |

Permissões Android usadas: `READ_PHONE_STATE` (estado da chamada, sem números),
`SYSTEM_ALERT_WINDOW` e, no Xiaomi, "janelas pop-up em segundo plano" (abrir o marcador com a
app fechada), `USE_FULL_SCREEN_INTENT` (abrir com o ecrã bloqueado). O instalador por ADB
concede-as todas.

## 5. Dados do lado do Motor

Módulo: `motor/chamadas.py`. Separador: **Chamadas** na interface (`app.py`).

**Fila (`call_queue`)**, por esta ordem de prioridade:
1. leads em `respondeu` (opção "Incluir quem respondeu");
2. chamadas de volta marcadas para hoje ou antes (`signals.callback_at`);
3. leads `qualificado` com `signals.channel = "chamada"` (e `revisao`, se ativado).

Ficam de fora: `optout`, `excluido`, `cliente`, telefones vazios, quem está na tabela `optouts`,
e quem já teve chamada registada hoje (exceto chamada de volta para hoje). Dentro de cada
prioridade: menos tentativas primeiro, depois mais avaliações no Google.

**Resultado (`record_call`)**:

| `resultado` | Texto | Estado do lead | Volta à fila |
|---|---|---|---|
| `interessado` | Atendeu: interessado | `interessado` | não (para voltar a ligar, usar `ligar_depois`) |
| `sem_interesse` | Atendeu: sem interesse | `contactado` | não |
| `ligar_depois` | Pediu para ligar mais tarde | mantém | na data `callback_at` |
| `nao_atendeu` | Não atendeu | mantém | sim, a partir de amanhã |
| `numero_errado` | Número errado ou inexistente | `excluido` | não |
| `remover` | Pediu para não ser contactado | `optout` + tabela `optouts` (telefone e email) + cancela emails pendentes | não |

Cada resultado grava:
- `events`: `type = "chamada"`, `lead_id`, `detail` = JSON
  `{"resultado", "texto", "notas", "duracao_s", "numero", "callback_at", "transcricao"?}`;
- `leads.signals`: `call_attempts` (+1), `last_call_outcome`, `callback_at` (só em `ligar_depois`);
- `leads.notes`: linha `[AAAA-MM-DD HH:MM chamada] <texto>: <notas>`.

## 6. Regras de compatibilidade

- Campos novos são sempre opcionais; quem lê ignora o que não conhece.
- Mudar o significado de um campo ou remover um endpoint obriga a subir `api_version` e a
  atualizar este documento nos dois projetos.
- O Motor verifica `api_version` em `/api/status` antes de usar funções novas.

## 7. Nota legal

Chamadas de marketing a pessoas singulares podem exigir consentimento prévio
(Lei 41/2004, art. 13.º-A; Diretriz CNPD 1/2022). O Motor mostra um aviso quando o lead tem NIF
de pessoa singular. A opção "Pediu para não ser contactado" é definitiva em todos os canais.
