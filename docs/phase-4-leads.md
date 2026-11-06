# Fase 4 — Leads e CRM inicial

## Implementado
- Migration de leads com vínculo à organização.
- Chave estrangeira composta garante que imóvel associado pertence à mesma organização.
- Atribuição vinculada à membership da própria organização.
- Criação, listagem paginada, consulta, atualização de status e atribuição.
- Filtros e operações sempre usam organizationId do principal autenticado.

## Endpoints
- GET /api/v1/leads?offset=0&limit=50&status=NEW
- GET /api/v1/leads/{leadId}
- POST /api/v1/leads
- PATCH /api/v1/leads/{leadId}/status
- PATCH /api/v1/leads/{leadId}/assignment

## Próximos módulos de CRM
Ainda faltam clientes, contatos completos, funil configurável, histórico de interações, tarefas, visitas, mensagens e relatórios. Validar os contratos com a interface legada antes de declarar compatibilidade.
