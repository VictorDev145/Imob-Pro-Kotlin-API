# Phase 5 — CRM, publicações e comunicação

## Entregue nesta fase
- Tarefas multiempresa com responsável, prazo, prioridade, status e vínculos opcionais a lead/imóvel.
- Visitas multiempresa com agendamento, duração, responsável e status.
- Anúncios ligados a imóveis da própria organização, com fluxo de rascunho/publicação/pausa/arquivamento.
- Caixa de notificações isolada por organização e usuário, com leitura individual/em lote.
- Conversas e mensagens com autorização por participação explícita na conversa.
- Registro de atividade para criação/alteração de tarefas e visitas.
- Favoritos e buscas salvas no schema, preparados para os endpoints de usuário.

## Segurança e integridade
- O organizationId sempre vem do principal autenticado.
- Consultas de tarefas, visitas, anúncios e notificações incluem tenant.
- FKs compostas impedem vincular leads, imóveis, conversas e membros de outra organização.
- Mensagens só podem ser consultadas/enviadas por participantes registrados na conversa.
- Responsáveis de tarefas/visitas precisam ser membros ativos da organização.
- A API não aceita organizationId em DTOs de escrita.

## Endpoints
- GET/POST /api/v1/crm/tasks
- PATCH /api/v1/crm/tasks/{id}/status
- GET/POST /api/v1/crm/visits
- PATCH /api/v1/crm/visits/{id}/status
- GET/POST /api/v1/listings
- PATCH /api/v1/listings/{id}/status
- GET /api/v1/notifications
- PATCH /api/v1/notifications/{id}/read
- PATCH /api/v1/notifications/read-all
- GET/POST /api/v1/conversations
- GET/POST /api/v1/conversations/{id}/messages
- POST /api/v1/conversations/{id}/read

## Limitações conhecidas
- property_images é apenas o catálogo de metadados; upload, validação de conteúdo, antivírus, geração de variantes e storage S3/compatível ainda não foram conectados.
- listings já possui fluxo básico, mas publicação em portais externos e geração de páginas públicas ainda não existem.
- Conversas atualmente começam com o criador como único participante; o fluxo de convite/gestão de participantes ainda precisa ser implementado.
- Notificações possuem inbox, mas os produtores de eventos e entrega assíncrona ainda precisam ser conectados.
- Favoritos e buscas salvas têm schema, mas faltam controllers/services e alertas automáticos.
- A integração da interface HTML/JS original continua pendente até concluir o adaptador de autenticação, contratos e compatibilidade de todos os endpoints.

## Validação
Executar ./gradlew test ktlintCheck e os testes Testcontainers com Docker ativo. A CI pode pular a suíte PostgreSQL quando Docker não estiver disponível; esse cenário não equivale a validar a migração contra PostgreSQL real.
