# Fase 3 — Inventário de imóveis

## Implementado nesta branch
- Migration PostgreSQL para inventário de imóveis com tenant obrigatório.
- Tipos, finalidade, status, endereço, características, áreas e valores.
- Referência legível única por empresa.
- Criação, consulta individual, listagem paginada, filtro por cidade/status, atualização e arquivamento lógico.
- Todas as operações filtram por organization_id derivado do principal autenticado.
- A API nunca aceita organizationId do cliente para definir o tenant.

## Endpoints
- GET /api/v1/properties?offset=0&limit=50&city=...&status=...
- GET /api/v1/properties/{propertyId}
- POST /api/v1/properties
- PUT /api/v1/properties/{propertyId}
- DELETE /api/v1/properties/{propertyId} (arquivamento lógico)

## Fora do escopo desta entrega
Upload/galeria de imagens, publicação de anúncios em canais, busca avançada, geocodificação e compatibilidade exata com o contrato legado. Estes itens exigem etapas próprias e integração testada com a interface.
