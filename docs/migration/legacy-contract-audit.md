# Auditoria de compatibilidade dos 67 contratos legados

## Estado da auditoria

**Parcial — não considerar os 67 contratos funcionalmente validados.** O manifesto em `compat-bff/legacy-route-coverage.json` e o teste de cobertura confirmam que cada rota está declarada como adapter, mapping ou disabled. Isso comprova presença de roteamento, não equivalência de payloads, IDs, permissões, status HTTP ou respostas com a aplicação Express original.

O repositório legado permanece inalterado. Os achados abaixo foram identificados comparando `VictorDev145/Imob-Pro/public/app.js`, `VictorDev145/Imob-Pro/server.js`, o BFF e os controllers Kotlin.

## Bloqueios confirmados

| Contrato legado | Evidência | Problema | Correção necessária |
|---|---|---|---|
| `POST /api/admin/users` | O formulário `saveOwner`/`saveAttendant` envia `name`, `email`, `phone`, `password` e `role`. O BFF encaminha somente `email` e `role` para criação de convite. | Nome, telefone e senha inicial são descartados. A interface informa “Acesso criado”, mas a conta só nasce após aceite do convite. | Definir contrato de provisionamento imediato ou atualizar o fluxo da interface para convite explícito; nunca armazenar senha enviada em convite sem decisão de segurança. |
| `POST /api/admin/visits` | O formulário legado envia `property_id` e `customer_id` numéricos e `visit_date`. | O adaptador genérico apenas converte snake_case para camelCase. O controller Kotlin exige `propertyId: UUID` e `scheduledAt: Instant`; portanto, `visitDate` não satisfaz o DTO e IDs numéricos não são UUIDs. | Criar adaptador explícito, resolver IDs legados por tabela de aliases e mapear data/horário e relacionamentos. |
| `POST /api/visits` | O formulário de solicitação legado também usa ID numérico de imóvel e data legada. | O contrato Kotlin disponível para solicitação pública autenticada é diferente e exige UUID e timestamp ISO. O encaminhamento genérico não traduz o contrato. | Especificar equivalência de fluxo e payload; resolver IDs antes de chamar o endpoint Kotlin. |
| `POST /api/admin/tasks` e `PATCH /api/admin/tasks/:id` | A interface envia `due_at`, `priority`, `notes` e status minúsculo como `done`. | O DTO Kotlin usa `dueAt`, `description`, enum de prioridade/status em maiúsculas e IDs UUID. A conversão snake_case não resolve nomes semanticamente diferentes nem enums. | Criar adaptadores explícitos de request/response e aliases para IDs. |
| IDs de imóveis no frontend | `PropertyResponse.id` e `PublicListing.propertyId` são UUIDs. O frontend legado interpola IDs sem aspas em handlers inline, por exemplo `ownerForm(${p.id})`, `editAdminProperty(${p.id})` e `changePropertyStatus(${p.id}, ...)`. | UUIDs não são literais numéricos JavaScript; os handlers gerados podem falhar antes de executar. O formulário de visita também usa `input type="number"`. | Adaptar o frontend servido pela nova aplicação para tratar IDs como strings, ou implementar aliases numéricos estáveis para todas as entidades e resolver cada ID recebido. Não alterar o repositório legado durante esta migração. |

## O que já está comprovado

- O BFF possui rotas explícitas para os contratos declarados no manifesto.
- O endpoint público `/api/properties/:id` encontra o anúncio por UUID de imóvel na API Kotlin.
- A API Kotlin aplica isolamento por organização em consultas privadas e valida a propriedade antes de operar imagens.
- O chat já possui aliases numéricos estáveis para usuários; esse mecanismo ainda não cobre imóveis, leads, tarefas, visitas ou outras entidades.
- O endpoint `POST /api/admin/users` foi intencionalmente convertido em convite, mas isso ainda não é equivalente ao fluxo de criação imediata da interface legada.

## Próxima ordem de correção

1. Definir a estratégia única de IDs para a interface: strings UUID com adaptação do frontend no novo repositório, ou aliases numéricos por entidade. Não misturar as duas estratégias.
2. Fechar os adaptadores explícitos de tarefas e visitas, incluindo campos, enums, IDs e formatos de resposta.
3. Resolver o fluxo de criação de usuários: provisionamento imediato com validação segura ou convite com experiência de aceite e mensagens corretas na interface.
4. Executar testes HTTP de contrato para cada rota habilitada, comparando payload e resposta com o Express original.
5. Só então marcar cada contrato como validado; manter `admin-recover` explicitamente desativado.

## Critério para concluir

Cada contrato deve ter ao menos um teste que exercite método, caminho, autenticação/autorização, payload válido e inválido, status HTTP, formato da resposta e isolamento tenant quando aplicável. Testes estáticos de presença de rota não contam como validação funcional.
